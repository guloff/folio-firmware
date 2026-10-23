add_executable(Fb2ToEpubTest
  Fb2ToEpubTest.cpp
  ${REPO_ROOT}/lib/Fb2/Fb2ToEpub.cpp
  ${REPO_ROOT}/lib/Fb2/StoredZipWriter.cpp
  ${REPO_ROOT}/lib/miniz/src/miniz_impl.c
  ${REPO_ROOT}/src/util/FootnoteExtract.cpp
)

target_include_directories(Fb2ToEpubTest PRIVATE
  ${REPO_ROOT}/lib/Fb2
  ${REPO_ROOT}/lib/Memory
  ${REPO_ROOT}/lib/miniz/src
  ${REPO_ROOT}/src/util
)

target_link_libraries(Fb2ToEpubTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
  expat
)

gtest_discover_tests(Fb2ToEpubTest)
