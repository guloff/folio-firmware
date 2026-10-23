add_executable(DictionaryMetaTest
  DictionaryMetaTest.cpp
  ${REPO_ROOT}/src/util/DictionaryMeta.cpp
)

target_include_directories(DictionaryMetaTest PRIVATE
  ${REPO_ROOT}/src/util
)

target_link_libraries(DictionaryMetaTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
)

gtest_discover_tests(DictionaryMetaTest)
