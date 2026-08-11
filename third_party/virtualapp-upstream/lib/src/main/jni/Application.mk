APP_ABI := arm64-v8a
# 与宿主及当前游戏本体的最低版本一致，避免链接阶段意外引入只在更低平台测试的 ABI 假设。
APP_PLATFORM := android-26
APP_STL := c++_static
APP_OPTIM := release
VA_ROOT          := $(call my-dir)
NDK_MODULE_PATH  := $(NDK_MODULE_PATH):$(VA_ROOT)
