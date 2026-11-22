#!/usr/bin/env bash
# Checks that DuckDB reads the files Uni writes, with the expected value in every column.
# A development-time check: committed tests do not need DuckDB.
#
# Needs DuckDB 1.3 or later (lambda syntax).
#
# Usage (from the repository root): uni/scripts/check-writer-with-duckdb.sh [duckdb]
set -euo pipefail

DUCKDB="${1:-duckdb}"
# DuckDB changes its output format for AI agents; the checks compare plain CSV
unset AI_AGENT
DIR="target/parquet-samples"

./sbt "uniJVM/Test/runMain wvlet.uni.parquet.WriteSampleFiles ${DIR}"

# Order.sample(i) in WriterTest.scala, recomputed in SQL. Each line counts the rows whose column
# differs from the expected value; every count must be 0.
orders_check() {
  local file="$1"
  "${DUCKDB}" -csv -noheader <<SQL
WITH t AS (SELECT * FROM '${file}'),
e AS (
  SELECT *,
    lower(lpad(to_hex(id::UBIGINT), 16, '0')) AS m,
    CASE WHEN id = 0 THEN repeat('0', 16)
         ELSE lower(to_hex((18446744073709551616::HUGEINT - id)::UBIGINT)) END AS l
  FROM t
)
SELECT
  count(*) = 300 AS row_count,
  count(*) FILTER (WHERE i32 IS DISTINCT FROM id * 3 - 100) AS i32,
  count(*) FILTER (WHERE i16 IS DISTINCT FROM id % 300) AS i16,
  count(*) FILTER (WHERE i8 IS DISTINCT FROM id % 100 - 50) AS i8,
  count(*) FILTER (WHERE f IS DISTINCT FROM (id / 4.0)::FLOAT) AS f,
  count(*) FILTER (WHERE d IS DISTINCT FROM id * 0.5) AS d,
  count(*) FILTER (WHERE flag IS DISTINCT FROM (id % 3 = 0)) AS flag,
  count(*) FILTER (WHERE name IS DISTINCT FROM 'name-' || (id % 17)) AS name,
  count(*) FILTER (WHERE note IS DISTINCT FROM CASE WHEN id % 4 = 0 THEN NULL ELSE 'note ' || id END) AS note,
  count(*) FILTER (WHERE ts IS DISTINCT FROM
    TIMESTAMPTZ '2026-01-01 00:00:00+00' + to_seconds(id) + to_microseconds(id)) AS ts,
  count(*) FILTER (WHERE uuid::VARCHAR IS DISTINCT FROM
    substr(m, 1, 8) || '-' || substr(m, 9, 4) || '-' || substr(m, 13, 4) || '-' ||
    substr(l, 1, 4) || '-' || substr(l, 5, 12)) AS uuid,
  count(*) FILTER (WHERE amount IS DISTINCT FROM (id * 1.25 - 50)::DECIMAL(38, 18)) AS amount,
  count(*) FILTER (WHERE color IS DISTINCT FROM ['Red', 'Green', 'Blue'][id % 3 + 1]) AS color,
  count(*) FILTER (WHERE tags IS DISTINCT FROM list_transform(range(id % 4), lambda x: 't' || x)) AS tags,
  count(*) FILTER (WHERE scores IS DISTINCT FROM
    list_transform(range(id % 3), lambda x: CASE WHEN x = 1 THEN NULL ELSE (x * id)::INT END)) AS scores,
  count(*) FILTER (WHERE attrs::VARCHAR IS DISTINCT FROM
    map_from_entries(list_transform(range(id % 3), lambda x: {'key': 'k' || x, 'value': x::INT}))::VARCHAR) AS attrs,
  count(*) FILTER (WHERE optAttrs::VARCHAR IS DISTINCT FROM
    CASE WHEN id % 5 = 0 THEN NULL ELSE MAP {'a': 'v' || id, 'b': NULL::VARCHAR} END::VARCHAR) AS opt_attrs,
  count(*) FILTER (WHERE address IS DISTINCT FROM
    {'city': 'city' || (id % 5), 'zip': CASE WHEN id % 2 = 0 THEN (10000 + id)::VARCHAR END}) AS address,
  count(*) FILTER (WHERE billing IS DISTINCT FROM
    CASE WHEN id % 3 <> 0 THEN {'city': 'billing', 'zip': NULL::VARCHAR} END) AS billing,
  count(*) FILTER (WHERE items IS DISTINCT FROM list_transform(range(id % 3),
    lambda x: {'name': 'item' || x, 'qty': (x + 1)::INT, 'price': (x + 0.5)::DECIMAL(38, 18)})) AS items,
  count(*) FILTER (WHERE matrix IS DISTINCT FROM
    CASE WHEN id % 6 = 0 THEN NULL ELSE [[id, id + 1], []::BIGINT[], [-1]] END) AS matrix
FROM e;
SQL
}

status=0
check() {
  local name="$1" result="$2" expected="$3"
  if [[ "${result}" == "${expected}" ]]; then
    echo "ok    ${name}"
  else
    echo "FAIL  ${name}: got ${result}, expected ${expected}"
    status=1
  fi
}

ZEROS="true$(printf ',0%.0s' {1..20})"
for f in orders orders-uncompressed orders-lz4_raw orders-zstd orders-gzip orders-plain; do
  check "${f}" "$(orders_check "${DIR}/${f}.parquet")" "${ZEROS}"
done

check "fallback" "$("${DUCKDB}" -csv -noheader -c "
  SELECT count(*), count(DISTINCT name), sum(id),
         bool_and(name = 'distinct-value-' || id)
  FROM '${DIR}/fallback.parquet'")" "20000,20000,199990000,true"

check "records" "$("${DUCKDB}" -csv -noheader -c "
  SELECT string_agg(concat_ws('|', id, name, epoch_ms(ts), day, price, doc), ';' ORDER BY id)
  FROM '${DIR}/records.parquet'")" \
  '"1|aina|1770091506789|2026-01-31|12.34|{""a"":1};2|leo|-0.50;3;4|x|0|1970-01-02|1.00|[1,2]"'

# Statistics: DuckDB skips row groups by min/max, so a selective filter reads less
check "statistics" "$("${DUCKDB}" -csv -noheader -c "
  SELECT stats_min, stats_max, stats_null_count
  FROM parquet_metadata('${DIR}/fallback.parquet')
  WHERE path_in_schema = 'id' AND row_group_id = 0")" "0,$(( $("${DUCKDB}" -csv -noheader -c "
  SELECT row_group_num_rows FROM parquet_metadata('${DIR}/fallback.parquet')
  WHERE path_in_schema = 'id' AND row_group_id = 0") - 1 )),0"

exit "${status}"
