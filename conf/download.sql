select req.request_id, req.request_time, req.user_name, req.user_emailaddress, req.user_format, res.response_time, res.response_code
	from request_info req 
	join result_info res on req.id = res.request_info_id
;