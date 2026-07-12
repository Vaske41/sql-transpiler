SELECT u.name, o.total FROM users AS u LEFT JOIN orders AS o ON o.user_id = u.id RIGHT JOIN payments AS p ON p.order_id = o.id;
