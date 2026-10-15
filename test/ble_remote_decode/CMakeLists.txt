add_executable(BleRemoteDecodeTest
  BleRemoteDecodeTest.cpp
  ${REPO_ROOT}/src/inklink/BleRemote.cpp
)

target_include_directories(BleRemoteDecodeTest PRIVATE
  ${REPO_ROOT}/src/inklink
)

target_link_libraries(BleRemoteDecodeTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
)

gtest_discover_tests(BleRemoteDecodeTest)
