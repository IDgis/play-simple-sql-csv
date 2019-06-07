package controllers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Inject;

import play.Configuration;
import play.Logger;
import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {
	
	private final String sql;
	
	private final String filenamePrefix;
	
	@Inject
	public Index(Configuration config) {
		filenamePrefix = config.getString("output.filenamePrefix");	
		String sqlFile = config.getString("sql.file");
		
		Logger.info("Using configuration: filenamePrefix=" + filenamePrefix + ", sqlFile=" + sqlFile);
		
		StringBuilder sqlBuilder = new StringBuilder();
		
		try(BufferedReader br = new BufferedReader(new FileReader(sqlFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				sqlBuilder
					.append(" ")
					.append(line.trim());
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException("couldn't read sql file", e);
		}
		
		sql = sqlBuilder.toString();
	}

	public Result index() throws Exception {
		LocalDateTime ldt = LocalDateTime.now();
		String dateTime = ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		response().setContentType("text/csv");
		response().setHeader(
				"Content-Disposition", "attachment; filename=\"" + 
				filenamePrefix + dateTime + ".csv\"");
		
		StringBuilder csv = new StringBuilder();
			
		try(
			Connection connection = DB.getConnection(false);
			PreparedStatement stmt = connection.prepareStatement(sql);
		) {
			try(
				ResultSet rs = stmt.executeQuery();	
			) {
				ResultSetMetaData rsm = rs.getMetaData();
				for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
					csv.append("\"" + rsm.getColumnName(i) + "\";");
				}

				csv.append(System.lineSeparator());

				while(rs.next()) {	
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

							csv.append("\"" + finalStr.replaceAll("[\\t\\n\\r]", " ") + "\";");
						} else if(o == null) {
							csv.append("\"" + "" + "\";");
						} else {
							csv.append("\"" + o + "\";");
						}
					}

					csv.append(System.lineSeparator());
				}
			}
		}
		
		return ok(csv.toString().getBytes());
	}
}
