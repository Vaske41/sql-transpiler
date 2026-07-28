SELECT u.id FROM users AS u LEFT JOIN archived AS a ON a.id = u.id UNION ALL SELECT u.id FROM users AS u RIGHT JOIN archived AS a ON a.id = u.id WHERE a.id IS NULL;
