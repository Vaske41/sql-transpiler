CREATE TABLE products (
  id INT NOT NULL PRIMARY KEY,
  sku VARCHAR(32) NOT NULL,
  price DECIMAL(10,2)
);
INSERT INTO products (id, sku, price) VALUES (1, 'A1', 9.99);
SELECT id, sku FROM products WHERE sku = 1 ORDER BY id;