@echo off
rem The demo CRM server on http://localhost:8094 (in-memory database, simulated calls). Needs Java 21:
rem set JAVA_HOME first if java on the PATH is older, e.g. set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
set "SPRING_CONFIG_ADDITIONAL_LOCATION=file:%~dp0demo.properties"
cd /d "%~dp0..\backend"
call "%~dp0..\backend\mvnw.cmd" -q spring-boot:run -Dspring-boot.run.useTestClasspath=true
