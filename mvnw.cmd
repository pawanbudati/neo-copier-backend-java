@echo off
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-21.0.11.10-hotspot"
set "MAVEN_DIR=%~dp0.mvn\apache-maven-3.9.9"

if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
    echo Downloading Apache Maven 3.9.9...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip', '%~dp0.mvn\maven.zip'); Expand-Archive -Path '%~dp0.mvn\maven.zip' -DestinationPath '%~dp0.mvn\' -Force; Remove-Item '%~dp0.mvn\maven.zip' -Force"
)

"%MAVEN_DIR%\bin\mvn.cmd" %*
