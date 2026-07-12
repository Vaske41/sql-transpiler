SELECT a.x FROM a INNER JOIN b ON a.id = b.a_id INNER JOIN c ON b.id = c.b_id WHERE c.flag = 1;
