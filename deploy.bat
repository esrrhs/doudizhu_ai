@echo off
cd java
pause
start https://central.sonatype.com/publishing
mvnw.cmd clean deploy -Prelease
pause
