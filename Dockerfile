#
# Build stage
#
FROM maven:3.6.0-jdk-11-slim AS build
ENV REFRESH_DATE=20260218_01
COPY src ./src
COPY pom.xml ./
RUN mvn -f ./pom.xml clean package

#
# Package stage
#
FROM eclipse-temurin:11-jre-focal
COPY --from=build ./target/*.jar /usr/local/lib/1.jar

EXPOSE 9281 8443

ENTRYPOINT ["java","-jar","/usr/local/lib/1.jar"]
