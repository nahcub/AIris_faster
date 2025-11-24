cmake_minimum_required(VERSION 3.22.1)
project("llama_integration")

add_subdirectory(llama.cpp)

add_library(
    airis SHARED
    native-lib.cpp
    prompt_generate.cpp
)

# Include 경로 설정 (llama.h를 찾기 위해)
target_include_directories(airis PRIVATE 
    llama.cpp
    llama.cpp/include
)

# 로그 라이브러리 링크 추가 (안드로이드 로깅용)
target_link_libraries(airis PRIVATE 
    llama
    log
    android
)

# JNI 함수 이름과 일치시키기 위한 설정
set_target_properties(airis PROPERTIES
    LIBRARY_OUTPUT_NAME "airis"
)
