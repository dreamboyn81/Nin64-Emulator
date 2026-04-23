cmake_minimum_required(VERSION 3.22.1)

project(ultrahle_core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED OFF)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LEGACY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../")
set(PORT_COMPAT "${CMAKE_CURRENT_LIST_DIR}/compat/port_compat.h")

set(LEGACY_SOURCES
    "${LEGACY_ROOT}/BOOT.C"
    "${LEGACY_ROOT}/CART.C"
    "${LEGACY_ROOT}/CPU.C"
    "${LEGACY_ROOT}/CPUC.C"
    "${LEGACY_ROOT}/DISASM.C"
    "${LEGACY_ROOT}/DLIST.C"
    "${LEGACY_ROOT}/HW.C"
    "${LEGACY_ROOT}/INIFILE.C"
    "${LEGACY_ROOT}/LOG.C"
    "${LEGACY_ROOT}/MEM.C"
    "${LEGACY_ROOT}/OS.C"
    "${LEGACY_ROOT}/PATCH.C"
    "${LEGACY_ROOT}/RDP.C"
    "${LEGACY_ROOT}/SLIST.C"
    "${LEGACY_ROOT}/SYM.C"
    "${LEGACY_ROOT}/SYMFIND.C"
    "${LEGACY_ROOT}/SYNC.C"
    "${LEGACY_ROOT}/TIMER.C"
    "${LEGACY_ROOT}/ZLIST.C"
    "${CMAKE_CURRENT_LIST_DIR}/android_port.c"
    "${CMAKE_CURRENT_LIST_DIR}/jni_bridge.c"
    "${CMAKE_CURRENT_LIST_DIR}/x_soft.c"
)

add_library(ultrahle_core SHARED ${LEGACY_SOURCES})

set_source_files_properties(
    "${LEGACY_ROOT}/BOOT.C"
    "${LEGACY_ROOT}/CART.C"
    "${LEGACY_ROOT}/CPU.C"
    "${LEGACY_ROOT}/CPUC.C"
    "${LEGACY_ROOT}/DISASM.C"
    "${LEGACY_ROOT}/DLIST.C"
    "${LEGACY_ROOT}/HW.C"
    "${LEGACY_ROOT}/INIFILE.C"
    "${LEGACY_ROOT}/LOG.C"
    "${LEGACY_ROOT}/MEM.C"
    "${LEGACY_ROOT}/OS.C"
    "${LEGACY_ROOT}/PATCH.C"
    "${LEGACY_ROOT}/RDP.C"
    "${LEGACY_ROOT}/SLIST.C"
    "${LEGACY_ROOT}/SYM.C"
    "${LEGACY_ROOT}/SYMFIND.C"
    "${LEGACY_ROOT}/SYNC.C"
    "${LEGACY_ROOT}/TIMER.C"
    "${LEGACY_ROOT}/ZLIST.C"
    PROPERTIES
    LANGUAGE C
)

target_include_directories(
    ultrahle_core
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/compat"
    "${LEGACY_ROOT}"
)

target_compile_options(
    ultrahle_core
    PRIVATE
    -O3
    -include
    "${PORT_COMPAT}"
    -fcommon
    -fms-extensions
    -Wno-deprecated-non-prototype
    -Wno-format-security
    -Wno-implicit-function-declaration
    -Wno-incompatible-pointer-types
    -Wno-int-conversion
    -Wno-int-to-pointer-cast
    -Wno-macro-redefined
    -Wno-pointer-to-int-cast
    -Wno-return-type
    -Wno-unused-but-set-variable
    -Wno-unused-function
    -Wno-unused-variable
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    ultrahle_core
    PRIVATE
    ${android-lib}
    ${log-lib}
    m
)
