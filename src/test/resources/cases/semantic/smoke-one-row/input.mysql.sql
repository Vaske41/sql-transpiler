DROP TABLE IF EXISTS smoke_one;
CREATE TABLE smoke_one (
  id INT NOT NULL PRIMARY KEY,
  label VARCHAR(16) NOT NULL
);
INSERT INTO smoke_one (id, label) VALUES (1, 'ok');
SELECT id, label FROM smoke_one ORDER BY id;
