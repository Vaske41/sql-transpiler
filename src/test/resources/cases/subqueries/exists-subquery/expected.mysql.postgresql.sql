SELECT id FROM users AS u WHERE EXISTS (SELECT 1 FROM orders AS o WHERE o.user_id = u.id) AND NOT EXISTS (SELECT 1 FROM bans AS b WHERE b.user_id = u.id);
