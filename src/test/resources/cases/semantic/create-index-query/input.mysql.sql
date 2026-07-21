DROP TABLE IF EXISTS idx_items;
CREATE TABLE idx_items (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL
);
CREATE INDEX idx_items_name ON idx_items (name);
INSERT INTO idx_items (id, name) VALUES
  (1, 'bravo'),
  (2, 'alpha'),
  (3, 'charlie');
SELECT id, name FROM idx_items ORDER BY name;
