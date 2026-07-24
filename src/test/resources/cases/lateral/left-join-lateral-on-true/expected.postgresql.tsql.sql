SELECT u.id, r.x FROM users AS u OUTER APPLY (SELECT u.id AS x) AS r;
