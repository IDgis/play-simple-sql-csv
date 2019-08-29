package controllers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Inject;

import play.Configuration;
import play.Logger;
import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {
	
	private String whereClause;
	
	private final String filenamePrefix;
	
	private final String sqlTemplate;
	
	@Inject
	public Index(Configuration config) {
		whereClause = config.getString("sql.whereClause");
		if(whereClause != null) whereClause = whereClause.trim();
		
		filenamePrefix = config.getString("output.filenamePrefix");
		String sqlFile = config.getString("sql.file");
		
		Logger.info("Using configuration: filenamePrefix=" + filenamePrefix + ", sqlFile=" + sqlFile);
		
		StringBuilder sqlTemplateBuilder = new StringBuilder();
		
		try(BufferedReader br = new BufferedReader(new FileReader(sqlFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				if(line.contains("%WHERE_CLAUSE%")) line = whereClause;
				
				sqlTemplateBuilder
					.append(" ")
					.append(line.trim());
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException("couldn't read sql file", e);
		}
		
		sqlTemplate = sqlTemplateBuilder.toString();
	}

	public Result index() {
		LocalDateTime now = LocalDateTime.now();
		String dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		String where = request().getQueryString("where");
		
		String sql = sqlTemplate;
		if(where == null && whereClause != null) sql = sqlTemplate.replace(whereClause, "");
		
		ByteArrayOutputStream baos = null;
		
		try(
			Connection connection = DB.getConnection(false);
			PreparedStatement stmt = connection.prepareStatement(sql);
		) {
			if(where != null && whereClause != null) stmt.setString(1, where);
			
			try(
				ResultSet rs = stmt.executeQuery();	
			) {
				baos = new ByteArrayOutputStream();
				
				StringBuilder header = new StringBuilder();
				ResultSetMetaData rsm = rs.getMetaData();
				for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
					header.append("\"" + rsm.getColumnName(i) + "\";");
				}
				
				baos.write(header.toString().getBytes(StandardCharsets.UTF_8));
				baos.write(System.lineSeparator().getBytes());
				
				while(rs.next()) {	
					StringBuilder line = new StringBuilder();
					
					for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
						Object o = rs.getObject(i);
						if(o instanceof String) {
							String str = (String) o;
							String finalStr = "";
							
							if(str.endsWith("\"")) {
								finalStr = str + "\"";
							} else {
								finalStr = str;
							}
							
							line.append("\"" + finalStr.replaceAll("[\\t\\n\\r]", " ") + "\";");
						} else if(o == null) {
							line.append("\"" + "" + "\";");
						} else {
							line.append("\"" + o + "\";");
						}
					}
					
					baos.write(line.toString().getBytes(StandardCharsets.UTF_8));
					baos.write(System.lineSeparator().getBytes());
				}
			} finally {
				if(baos != null) baos.close();
			}
		} catch(IOException ioe) {
			ioe.printStackTrace();
		} catch(SQLException sqle) {
			sqle.printStackTrace();
		}
		
		if(baos == null) return internalServerError();
		
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		
		response().setContentType("text/csv; charset=utf-8");
		response().setHeader(
				"Content-Disposition", "attachment; filename=\"" + 
				filenamePrefix + dateTime + ".csv\"");
		
		return ok(bais).as("UTF-8").as("text/csv");
	}
}
