SELECT u.id, t.x FROM users AS u INNER JOIN (SELECT 1 AS x) AS t ON u.id = t.x;
