@echo off
setlocal
set DIR=%~dp0
if defined JAVA_HOME (set JAVA_EXE=%JAVA_HOME%\bin\java.exe) else (set JAVA_EXE=java.exe)
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%TEMP%\WhoKnowsQuotesGradle"
set CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar;%DIR%gradle\wrapper\gradle-wrapper-shared-9.3.1.jar;%DIR%gradle\wrapper\gradle-cli-9.3.1.jar;%DIR%gradle\wrapper\gradle-files-9.3.1.jar
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal

