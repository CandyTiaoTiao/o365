package hqr.o365.service;

import java.util.HashMap;
import java.util.List;
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

import com.alibaba.fastjson.JSON;

import hqr.o365.dao.TaMasterCdRepo;
import hqr.o365.dao.TaOfficeInfoRepo;
import hqr.o365.domain.OfficeUser;
import hqr.o365.domain.TaMasterCd;
import hqr.o365.domain.TaOfficeInfo;

@Service
public class CreateOfficeUser {
	private RestTemplate restTemplate = new RestTemplate();
	
	@Autowired
	private TaOfficeInfoRepo repo;
	
	@Autowired
	private ValidateAppInfo vai;
	
	@Autowired
	private TaMasterCdRepo tmr;
	
	@Autowired
	private GetOrganizationInfo goi;
	
	@Value("${UA}")
    private String ua;
	
	@CacheEvict(value= {"cacheOfficeUser","cacheOfficeUserSearch","cacheLicense"}, allEntries = true)
	public HashMap<String, String> createCommonUser(String mailNickname, String userPrincipalName, String displayName, String licenses, String userPwd){
		HashMap<String, String> map = new HashMap<String, String>();
		String forceInd = "Y";
		Optional<TaMasterCd> opt = tmr.findById("FORCE_CHANGE_PASSWORD");
		if(opt.isPresent()) {
			TaMasterCd cd = opt.get();
			forceInd = cd.getCd();
		}

		OfficeUser ou = new OfficeUser();
		ou.setMailNickname(mailNickname);
		ou.setUserPrincipalName(userPrincipalName);
		ou.setDisplayName(displayName);
		ou.getPasswordProfile().setPassword(userPwd);
		if(!"Y".equals(forceInd)) {
			ou.getPasswordProfile().setForceChangePasswordNextSignIn(false);
		}
		
		// 获取选中全局
		List<TaOfficeInfo> list = repo.findBySelected("是");
		if(list!=null&&list.size()>0) {
			TaOfficeInfo ta = list.get(0);
			String accessToken = "";
			if(vai.checkAndGet(ta.getTenantId(), ta.getAppId(), ta.getSecretId())) {
				accessToken = vai.getAccessToken();
			}
			
			if(!"".equals(accessToken)) {
				// 1. 设置 UsageLocation (分配许可证的必要前提)
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
						String message = "成功创建用户" + ou.getUserPrincipalName() + "<br>";
						map.put("status", "0");
						System.out.println("成功创建用户：" + ou.getUserPrincipalName());
						
						// 2. 关键优化：创建后强制等待 5 秒，确保云端目录同步完成
						if(licenses!=null&&!"".equals(licenses)) {
							System.out.println("等待云端同步 5 秒...");
							Thread.sleep(5000); 
							
							String acs [] = licenses.split(",");
							for (String license : acs) {
								String licenseJson = "{\"addLicenses\": [{\"disabledPlans\": [],\"skuId\": \""+license+"\",}],\"removeLicenses\": [ ]}";
								String licenseEndpoint = "https://graph.microsoft.com/v1.0/users/"+ou.getUserPrincipalName()+"/assignLicense";
								
								HttpEntity<String> requestEntity2 = new HttpEntity<String>(licenseJson, headers);
								
								// 3. 关键优化：引入 3 次重试逻辑
								boolean licenseFlag = false;
								for(int i=1; i<=3; i++) {
									try {
										System.out.println("第 " + i + " 次尝试分配订阅：" + license);
										ResponseEntity<String> response2 = restTemplate.postForEntity(licenseEndpoint, requestEntity2, String.class);
										if(response2.getStatusCodeValue()==200) {
											message += "成功分配订阅：" + license + "<br>";
											licenseFlag = true;
											break;
										}
									} catch (Exception ex) {
										System.err.println("分配失败，等待重试...");
										Thread.sleep(2000); // 失败后再等 2 秒
									}
								}
								
								if(!licenseFlag) {
									message += "<span style='color:red;'>分配订阅：" + license + " 最终失败（已重试）</span><br>";
								}
							}
						}
						
						map.put("message", message);

						// 4. 创建 Group (如有配置)
						Optional<TaMasterCd> cgfu = tmr.findById("CREATE_GRP_FOR_USER");
						if(cgfu.isPresent() && "Y".equals(cgfu.get().getCd())) {
							Thread.sleep(1000);
							createGroupForUser(mailNickname, userPrincipalName, accessToken);
						}
					}
					else {
						map.put("status", "1");
						map.put("message", "失败，创建用户未能获得预期的返回值201");
					}
				}
				catch (Exception e) {
					e.printStackTrace();
					map.put("status", "1");
					map.put("message", "无法创建用户 " + e.toString());
				}
			}
			else {
				map.put("status", "1");
				map.put("message", "获取token失败，请确认全局的有效性");
			}
		}
		else {
			map.put("status", "1");
			map.put("message", "请先选择一个全局");
		}
		
		return map;
	}

	public void createGroupForUser(String mailNickname, String userPrincipalName, String accessToken) {
		String json = "{\"displayName\": \""+mailNickname+"\",\"expirationDateTime\": null,\"groupTypes\": [\"Unified\"],\"mailEnabled\": false,\"mailNickname\": \""+mailNickname+"\",\"securityEnabled\": false,\"visibility\": \"private\",\"owners@odata.bind\": [\"https://graph.microsoft.com/v1.0/users/"+userPrincipalName+"\"]}";
		String endpoint = "https://graph.microsoft.com/v1.0/groups";
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.USER_AGENT, ua);
		headers.add("Authorization", "Bearer "+accessToken);
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(json, headers);
		try {
			ResponseEntity<String> response = restTemplate.postForEntity(endpoint, requestEntity, String.class);
			if(response.getStatusCodeValue()==201) {
				System.out.println("成功创建Group:"+mailNickname);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
