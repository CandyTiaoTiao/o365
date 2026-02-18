package hqr.o365.service;

import java.util.Date;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException.BadRequest;

import com.alibaba.fastjson.JSON;

import hqr.o365.dao.TaInviteInfoRepo;
import hqr.o365.dao.TaOfficeInfoRepo;
import hqr.o365.domain.OfficeUser;
import hqr.o365.domain.TaInviteInfo;
import hqr.o365.domain.TaOfficeInfo;

@Service
public class CreateOfficeUserByInviteCd {
	private RestTemplate restTemplate = new RestTemplate();
	
	@Autowired
	private TaOfficeInfoRepo repo;
	
	@Autowired
	private ValidateAppInfo vai;
	
	@Autowired
	private TaInviteInfoRepo tii;
	
	@Autowired
	private GetOrganizationInfo goi;
	
	@Value("${UA}")
    private String ua;
	
	@CacheEvict(value= {"cacheOfficeUser","cacheInviteInfo","cacheOfficeUserSearch"}, allEntries = true)
	public String createCommonUser(String mailNickname, String displayName, String inviteCd, String password){
		String resultMsg = "失败";
		Optional<TaInviteInfo> opt = tii.findById(inviteCd);
		if(opt.isPresent()) {
			TaInviteInfo tiiDo = opt.get();
			
			Date currentDt = new Date();
			Date startDt = tiiDo.getStartDt();
			Date endDt = tiiDo.getEndDt();
			
			if(startDt!=null&&startDt.after(currentDt)) {
				return "此邀请码尚未生效";
			}
			
			if(endDt!=null&&endDt.before(currentDt)) {
				return "此邀请码已过期";
			}
			
			if("1".equals(tiiDo.getInviteStatus())){
				// 状态改为：使用中
				tiiDo.setInviteStatus("2");
				tii.save(tiiDo);
				
				Optional<TaOfficeInfo> opt1 = repo.findById(tiiDo.getSeqNo());
				if(opt1.isPresent()) {
					TaOfficeInfo ta = opt1.get();
					String accessToken = "";
					if(vai.checkAndGet(ta.getTenantId(), ta.getAppId(), ta.getSecretId())) {
						accessToken = vai.getAccessToken();
					}
					
					if(!"".equals(accessToken)) {
						String userPrincipalName = mailNickname + tiiDo.getSuffix();
						OfficeUser ou = new OfficeUser();
						ou.setMailNickname(mailNickname);
						ou.setUserPrincipalName(userPrincipalName);
						ou.setDisplayName(displayName);
						ou.getPasswordProfile().setPassword(password);
						ou.getPasswordProfile().setForceChangePasswordNextSignIn(true);
						
						// 1. 设置 UsageLocation (分配许可前提)
						String loc = goi.getUsageLocation(accessToken);
						ou.setUsageLocation(loc == null ? "CN" : loc);
						
						String createUserJson = JSON.toJSONString(ou);
						String endpoint = "https://graph.microsoft.com/v1.0/users";
						HttpHeaders headers = new HttpHeaders();
						headers.set(HttpHeaders.USER_AGENT, ua);
						headers.add("Authorization", "Bearer "+accessToken);
						headers.setContentType(MediaType.APPLICATION_JSON);
						HttpEntity<String> requestEntity = new HttpEntity<String>(createUserJson, headers);
						
						try {
							ResponseEntity<String> response = restTemplate.postForEntity(endpoint, requestEntity, String.class);
							if(response.getStatusCodeValue()==201) {
								System.out.println("成功创建用户：" + ou.getUserPrincipalName());
								
								// 2. 关键优化：等待 5 秒确保云端目录同步
								Thread.sleep(5000);
								
								// 分配许可逻辑
								String licenses = tiiDo.getLicenses();
								if(licenses!=null&&!"".equals(licenses)) {
									String acs [] = licenses.split(",");
									boolean allSuccess = true;
									
									for (String license : acs) {
										String licenseJson = "{\"addLicenses\": [{\"disabledPlans\": [],\"skuId\": \""+license+"\",}],\"removeLicenses\": [ ]}";
										String licenseEndpoint = "https://graph.microsoft.com/v1.0/users/"+ou.getUserPrincipalName()+"/assignLicense";
										HttpEntity<String> requestEntity2 = new HttpEntity<String>(licenseJson, headers);
										
										// 3. 关键优化：3次重试机制
										boolean currentLicenseFlag = false;
										for(int i=1; i<=3; i++) {
											try {
												System.out.println("第" + i + "次尝试分配订阅：" + license);
												ResponseEntity<String> response2 = restTemplate.postForEntity(licenseEndpoint, requestEntity2, String.class);
												if(response2.getStatusCodeValue()==200) {
													currentLicenseFlag = true;
													break;
												}
											} catch (Exception ex) {
												System.err.println("重试间隔等待...");
												Thread.sleep(2000);
											}
										}
										if(!currentLicenseFlag) allSuccess = false;
									}
									
									if(allSuccess) {
										tiiDo.setResult(ou.getUserPrincipalName()+"|"+password);
										tiiDo.setInviteStatus("3");
										tii.save(tiiDo);
										resultMsg = "0|"+ou.getUserPrincipalName();
									} else {
										tiiDo.setResult("部分或全部订阅分配失败");
										tiiDo.setInviteStatus("4");
										tii.save(tiiDo);
										resultMsg = "分配订阅出错";
									}
								} else {
									// 无需分配许可的情况
									tiiDo.setResult(ou.getUserPrincipalName()+"|"+password);
									tiiDo.setInviteStatus("3");
									tii.save(tiiDo);
									resultMsg = "0|"+ou.getUserPrincipalName();
								}
							} else {
								tiiDo.setInviteStatus("4");
								tiiDo.setResult("创建用户未能获得201返回");
								tii.save(tiiDo);
								resultMsg = "创建用户出错";
							}
						} catch (Exception e) {
							if(e instanceof BadRequest) {
								String responeBody = ((BadRequest) e).getResponseBodyAsString();
								if(responeBody.indexOf("same value")>=0) {
									tiiDo.setResult("此前缀已存在");
									tiiDo.setInviteStatus("1");
									tii.save(tiiDo);
									resultMsg = "此前缀已存在，请选择一个另外的前缀";
								} else {
									tiiDo.setInviteStatus("4");
									tiiDo.setResult("Bad Request: " + responeBody);
									tii.save(tiiDo);
									resultMsg = "创建失败(BadRequest)";
								}
							} else {
								tiiDo.setInviteStatus("4");
								tiiDo.setResult("系统异常: " + e.toString());
								tii.save(tiiDo);
								resultMsg = "无法创建用户";
							}
						}
					} else {
						tiiDo.setInviteStatus("4");
						tiiDo.setResult("获取Token失败");
						tii.save(tiiDo);
						resultMsg = "无效的全局";
					}
				} else {
					tiiDo.setInviteStatus("4");
					tiiDo.setResult("全局信息已丢失");
					tii.save(tiiDo);
					resultMsg = "不存在的全局";
				}
			} else if("2".equals(tiiDo.getInviteStatus())){
				resultMsg = "此邀请码正被使用中";
			} else if("3".equals(tiiDo.getInviteStatus())){
				resultMsg = "此邀请码已使用";
			} else if("4".equals(tiiDo.getInviteStatus())){
				resultMsg = "此邀请码使用出现错误";
			} else {
				resultMsg = "无效的邀请码状态";
			}
		} else {
			resultMsg = "无效的邀请码";
		}
		
		return resultMsg;
	}
}
