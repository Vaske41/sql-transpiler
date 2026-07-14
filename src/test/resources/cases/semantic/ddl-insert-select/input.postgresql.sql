DROP TABLE IF EXISTS items;
CREATE TABLE items (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  price DECIMAL(10,2) NOT NULL
);
INSERT INTO items (id, name, price) VALUES
  (1, 'cheap', 5.00),
  (2, 'mid', 15.50),
  (3, 'dear', 99.99);
SELECT id, name, price FROM items WHERE price >= 10.00 ORDER BY id;
