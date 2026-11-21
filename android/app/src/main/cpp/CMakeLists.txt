cmake_minimum_required(VERSION 3.22.1)
project(microwifi_noise C)

set(RNNOISE ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)
set(RNNOISE_SOURCES
    ${RNNOISE}/src/denoise.c ${RNNOISE}/src/rnn.c ${RNNOISE}/src/rnn_data.c
    ${RNNOISE}/src/rnn_reader.c ${RNNOISE}/src/pitch.c ${RNNOISE}/src/kiss_fft.c
    ${RNNOISE}/src/celt_lpc.c)

if(ANDROID)
    add_library(microwifi_noise SHARED noise_jni.c noise_pcm.c ${RNNOISE_SOURCES})
    set_target_properties(microwifi_noise PROPERTIES C_VISIBILITY_PRESET hidden)
    target_include_directories(microwifi_noise PRIVATE ${RNNOISE}/include)
    target_compile_options(microwifi_noise PRIVATE -O2)
    target_link_libraries(microwifi_noise PRIVATE m)
else()
    add_library(microwifi_noise_host SHARED noise_pcm.c ${RNNOISE_SOURCES})
    set_target_properties(microwifi_noise_host PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
    target_include_directories(microwifi_noise_host PRIVATE ${RNNOISE}/include)
    if(NOT MSVC)
        target_compile_options(microwifi_noise_host PRIVATE -O2)
        target_link_libraries(microwifi_noise_host PRIVATE m)
    endif()
endif()
