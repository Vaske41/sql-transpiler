SELECT u.id, r.x FROM users AS u CROSS APPLY (SELECT u.id AS x) AS r;
