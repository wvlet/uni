-- Writes the variant and geometry fixtures: duckdb -f make_variant_geometry.sql (DuckDB 2.0)
-- every primitive type, one per row, unshredded where DuckDB can't pick a common type
COPY (
  SELECT * FROM (VALUES
    (0, NULL::VARIANT),
    (1, true::VARIANT),
    (2, 42::TINYINT::VARIANT),
    (3, 4242::SMALLINT::VARIANT),
    (4, 424242::INTEGER::VARIANT),
    (5, 42424242424::BIGINT::VARIANT),
    (6, 1.5::DOUBLE::VARIANT),
    (7, 2.5::FLOAT::VARIANT),
    (8, 12.34::DECIMAL(4,2)::VARIANT),
    (9, 1234567890.12::DECIMAL(18,2)::VARIANT),
    (10, 123456789012345678901234.5678::DECIMAL(38,4)::VARIANT),
    (11, DATE '2026-11-22'::VARIANT),
    (12, TIMESTAMPTZ '2026-11-22 01:02:03.123456+00'::VARIANT),
    (13, TIMESTAMP '2026-11-22 01:02:03.123456'::VARIANT),
    (14, '\xAA\xBB'::BLOB::VARIANT),
    (15, 'a short string'::VARIANT),
    (16, repeat('long ', 20)::VARIANT),
    (17, TIME '01:02:03.123456'::VARIANT),
    (18, TIMESTAMP_NS '2026-11-22 01:02:03.123456789'::VARIANT),
    (19, '12345678-1234-5678-1234-567812345678'::UUID::VARIANT),
    (20, {'a': 1, 'b': [1, 2, 3], 'c': {'d': 'x'}}::VARIANT),
    (21, [1::VARIANT, 'two'::VARIANT, NULL::VARIANT, [3.5::VARIANT]::VARIANT]::VARIANT),
    (22, {'b': 1, 'a': 2}::VARIANT),
    (23, false::VARIANT)
  ) t(id, v)
) TO 'variant_types.parquet';
-- a shredded object: x and y are typed; z is in value for some rows
COPY (
  SELECT i AS id, CASE WHEN i = 3 THEN NULL WHEN i = 2 THEN {'x': i, 'y': 'n' || i, 'z': true}::VARIANT WHEN i = 4 THEN 'not an object'::VARIANT WHEN i = 5 THEN {'x': 'str', 'y': 'n5'}::VARIANT ELSE {'x': i, 'y': 'n' || i}::VARIANT END AS v FROM range(6) t(i)
) TO 'variant_object.parquet';
-- a shredded array
COPY (
  SELECT i AS id, CASE WHEN i = 2 THEN 'scalar'::VARIANT ELSE [i, i + 1]::VARIANT END AS v FROM range(4) t(i)
) TO 'variant_array.parquet';
-- geometry columns as WKB, with and without a CRS. GeoParquet 2 annotates them as GEOMETRY.
COPY (
  SELECT * FROM (VALUES
    (1, 'POINT(1 2)'::GEOMETRY, 'LINESTRING(0 0, 1 1)'::GEOMETRY('OGC:CRS84')),
    (2, NULL, NULL)
  ) t(id, g, h)
) TO 'geometry.parquet' (GEOPARQUET_VERSION 'V2');
