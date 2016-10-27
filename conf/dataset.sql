with metadata_as_xml as (	
	select 
		sd.id,
		sd.identification,
		xmlparse(document convert_from(document, 'utf-8')) as document,
		(
			select array_agg(gl.name || ':' || psd.layer_name) 
			from publisher.dataset d
			join publisher.published_service_dataset psd on psd.dataset_id = d.id
			join publisher.published_service ps on ps.service_id = psd.service_id
			join publisher.service s on s.id = ps.service_id
			join publisher.generic_layer gl on gl.id = s.generic_layer_id
			join publisher.environment e on e.id = ps.environment_id
			where d.source_dataset_id = sd.id) layer_names,
		exists (
			select * from publisher.dataset d
			join publisher.published_service_dataset psd on psd.dataset_id = d.id
			join publisher.published_service ps on ps.service_id = psd.service_id			
			join publisher.environment e on e.id = ps.environment_id
			where d.source_dataset_id = sd.id and not e.confidential) in_publieke_service
	from publisher.source_dataset_metadata sdm
	join publisher.source_dataset sd on sd.id = sdm.source_dataset_id
) 
select
	id,
	identification,
	(xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:citation/gmd:CI_Citation/gmd:title/gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[])[1] title,
	(xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:citation/gmd:CI_Citation/gmd:alternateTitle/gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[])[1] alternate_title,
	(xpath(
		'/gmd:MD_Metadata/gmd:fileIdentifier/gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[])[1] file_identifier,
	(xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:citation/gmd:CI_Citation/gmd:identifier/gmd:MD_Identifier/'
		|| 'gmd:code/gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[])[1] md_identifier,
	array_to_string(xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:resourceConstraints/gmd:MD_LegalConstraints/gmd:otherConstraints/'
		|| 'gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[], '|') other_constraints,
	array_to_string(xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:resourceConstraints/gmd:MD_Constraints/gmd:useLimitation/'
		|| 'gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[], '|') use_limitation,

	case when not xpath(
		'/gmd:MD_Metadata/gmd:identificationInfo/gmd:MD_DataIdentification/'
		|| 'gmd:resourceConstraints/gmd:MD_Constraints/gmd:useLimitation/'
		|| 'gco:CharacterString/text()',
		document, 
		array[
			array['gmd', 'http://www.isotc211.org/2005/gmd'],
			array['gco', 'http://www.isotc211.org/2005/gco']
		])::text[] @> array['Downloadable data'] and in_publieke_service
		then 'Ja' 
		else 'Nee'
	end feitelijk_onterecht_publiek,
	array_to_string(layer_names, '|') layer_names,
	(select string_agg(identification || '|' || coalesce(http_status::text, '?'), ' ')
	from publisher.source_dataset_metadata_attachment_error	
	where source_dataset_id = max.id) http_errors
from metadata_as_xml max;