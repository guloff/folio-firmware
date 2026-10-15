add_executable(RussianMorphologyTest
  RussianMorphologyTest.cpp
  ${REPO_ROOT}/src/util/RussianMorphology.cpp
)

target_include_directories(RussianMorphologyTest PRIVATE
  ${REPO_ROOT}/src/util
)

target_link_libraries(RussianMorphologyTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
)

gtest_discover_tests(RussianMorphologyTest)
