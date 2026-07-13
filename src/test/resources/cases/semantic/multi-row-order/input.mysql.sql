DROP TABLE IF EXISTS nums;
CREATE TABLE nums (
  id INT NOT NULL PRIMARY KEY,
  v VARCHAR(8) NOT NULL
);
INSERT INTO nums (id, v) VALUES (3, 'c'), (1, 'a'), (2, 'b');
SELECT id, v FROM nums ORDER BY id;
