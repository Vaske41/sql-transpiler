DROP TABLE IF EXISTS dst_items;
DROP TABLE IF EXISTS src_items;
CREATE TABLE src_items (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  price DECIMAL(10,2) NOT NULL
);
CREATE TABLE dst_items (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  price DECIMAL(10,2) NOT NULL
);
INSERT INTO src_items (id, name, price) VALUES
  (1, 'cheap', 5.00),
  (2, 'mid', 15.50),
  (3, 'dear', 99.99);
INSERT INTO dst_items (id, name, price)
SELECT id, name, price FROM src_items WHERE price >= 10.00;
SELECT id, name, price FROM dst_items ORDER BY id;
