@echo off
rem repo-intel launcher for Windows. See the sibling `repo-intel` script for the rationale.
rem
rem   REPO_INTEL_JAR   where the jar is, if it is not beside this script
rem   JAVA_OPTS        JVM flags; -Xmx is the one that matters on a large repository
setlocal

set "HERE=%~dp0"
set "JAR=%REPO_INTEL_JAR%"

if "%JAR%"=="" (
    rem Beside the script when installed; under target\ when run from a checkout.
    if exist "%HERE%repo-intel.jar" (
        set "JAR=%HERE%repo-intel.jar"
    ) else if exist "%HERE%..\apps\cli\target\repo-intel.jar" (
        set "JAR=%HERE%..\apps\cli\target\repo-intel.jar"
    )
)

if "%JAR%"=="" goto :nojar
if not exist "%JAR%" goto :nojar

set "JAVA_CMD=java"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"

"%JAVA_CMD%" %JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:nojar
echo repo-intel: cannot find repo-intel.jar. 1>&2
echo Put it beside this script, or set REPO_INTEL_JAR to its path. 1>&2
echo From a checkout of this repository: mvn -DskipTests package 1>&2
exit /b 1
