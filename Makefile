# Distress Detector developer commands. Override JAVA_HOME, GRADLE, SDK, or ADB as needed.

-include .local-toolchain.mk

ifeq ($(OS),Windows_NT)
SHELL := cmd.exe
.SHELLFLAGS := /C
GRADLE ?= "$(LOCALAPPDATA)\Programs\Gradle\gradle-8.13\bin\gradle.bat"
SDK ?= $(LOCALAPPDATA)\Android\Sdk
ADB ?= "$(SDK)\platform-tools\adb.exe"
ANDROID_USER_HOME := $(CURDIR)\.android-user
ANDROID_SDK_HOME := $(CURDIR)\.android-sdk-home
ADB_RUN = set "HOME=$(ANDROID_USER_HOME)" && set "ANDROID_USER_HOME=$(ANDROID_USER_HOME)" && $(ADB)
GRADLE_USER_HOME ?= $(CURDIR)\.gradle
PROJECT_TMP := $(CURDIR)\.tmp
JAVA_RUN = set "JAVA_HOME=$(JAVA_HOME)" && set "PATH=$(JAVA_HOME)\bin;%PATH%" && set "HOME=$(ANDROID_USER_HOME)" && set "ANDROID_USER_HOME=$(ANDROID_USER_HOME)" && set "GRADLE_USER_HOME=$(GRADLE_USER_HOME)" && set "TEMP=$(PROJECT_TMP)" && set "TMP=$(PROJECT_TMP)" && $(GRADLE)
else
SHELL := /bin/sh
GRADLE ?= gradle
SDK ?= $(ANDROID_HOME)
ADB ?= adb
ADB_RUN = $(ADB)
endif

APP_ID := com.distressdetector
APK := app/build/outputs/apk/debug/app-debug.apk

.PHONY: help setup build build-configured apk install install-configured run stop logs devices uninstall clean

help:
	@echo Targets: setup build apk install run stop logs devices uninstall clean

# Validate and summarize the already-installed toolchain. SDK and Gradle paths
# are project defaults; Java must be discoverable or supplied through JAVA_HOME.
setup:
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(CURDIR)\tools\setup.ps1" -ProjectRoot "$(CURDIR)"

build: setup
	$(MAKE) --no-print-directory build-configured

build-configured:
	$(JAVA_RUN) --no-daemon assembleDebug

apk: build
	@echo Debug APK: $(APK)

install: setup
	$(MAKE) --no-print-directory install-configured

install-configured:
	$(JAVA_RUN) --no-daemon installDebug

run: install
	$(ADB_RUN) shell am start -n $(APP_ID)/.MainActivity

stop:
	$(ADB_RUN) shell am force-stop $(APP_ID)

logs:
	$(ADB_RUN) logcat -s AndroidRuntime:E DistressDetector:V

devices:
	$(ADB_RUN) devices -l

uninstall:
	$(ADB_RUN) uninstall $(APP_ID)

clean:
	$(JAVA_RUN) --no-daemon clean
