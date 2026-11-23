# Files from apache/arrow-testing

These files come from https://github.com/apache/arrow-testing
(`data/arrow-ipc-stream/integration/1.0.0-littleendian` and `2.0.0-compression`, commit
5a26d35785ff8761e56d79eebff971f6f737e18c), licensed under the Apache License 2.0 (`LICENSE.txt`).
Arrow C++ wrote them. Each `.json.gz` describes the values of the `.stream` and `.arrow_file` with
the same name, in the Arrow integration JSON format.

`ArrowGoldenTest` reads them with the test decoder (`ArrowTestReader`) and compares every value
with the JSON, so the decoder can be trusted to read what uni's writer produces.
