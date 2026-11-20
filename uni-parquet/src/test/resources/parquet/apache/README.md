# Files from apache/parquet-testing

These files come from https://github.com/apache/parquet-testing (`data/`, commit
0e00127a431f83388d38eebb22cf644d06e86a15), licensed under the Apache License 2.0 (`LICENSE.txt`).
They were written by many writers (parquet-java, Impala, Spark, pyarrow, parquet-rs) and cover
encodings, codecs, and legacy schema layouts that DuckDB does not produce.

Files the reader rejects on purpose:

- `nation.dict-malformed.parquet`: a dictionary page whose value count is larger than its data
- `encrypt_columns_and_footer.parquet.encrypted`, `encrypt_columns_plaintext_footer.parquet.encrypted`:
  modular encryption is not supported
