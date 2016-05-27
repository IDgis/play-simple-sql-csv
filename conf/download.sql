select req.request_id, req.request_time, req.download -> 'ft' ->> 'crs' as crs, req.download -> 'ft' ->> 'name' as title, 
	req.download -> 'ft' ->> 'serviceUrl' as serice_url, req.download -> 'ft' ->> 'wfsMimetype' as wfs_mimetype, 
	req.download -> 'ft' ->> 'serviceVersion' as service_version, req.download ->> 'name' as uuid, req.user_name, req.user_emailaddress, 
	req.user_format, res.response_time, res.response_code
		from request_info req 
		join result_info res on req.id = res.request_info_id
;