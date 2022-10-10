-- Creates a Table.

-- Drop the table if it exists;
DROP TABLE IF EXISTS usermetric;

-- Create the usermetric table with 10 fields and 1 double value and Timestamp as key
CREATE TABLE usermetric(
  ID INT IDENTITY(1,1) PRIMARY KEY,
  YCSB_KEY TIMESTAMP,
  VALUE DOUBLE PRECISION,
  TAG0 VARCHAR, TAG1 VARCHAR,
  TAG2 VARCHAR, TAG3 VARCHAR,
  TAG4 VARCHAR, TAG5 VARCHAR,
  TAG6 VARCHAR, TAG7 VARCHAR,
  TAG8 VARCHAR, TAG9 VARCHAR);
