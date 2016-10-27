package controllers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import javax.inject.Inject;

import org.joda.time.LocalDate;

import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;
import play.Configuration;

public class Index extends Controller {
	
	private final String sql;
	
	private final String filenamePrefix;
	
	@Inject
	public Index(Configuration config) {
		filenamePrefix = config.getString("output.filenamePrefix");		
		String sqlFile = config.getString("sql.file");
		
		StringBuilder sqlBuilder = new StringBuilder();
		
		try(BufferedReader br = new BufferedReader(new FileReader(sqlFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				sqlBuilder.append(line);
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException("couldn't read sql file", e);
		}
		
		sql = sqlBuilder.toString();
	}

	public Result index() throws Exception {
		LocalDate ld = LocalDate.now();
		
		response().setContentType("text/csv");
		response().setHeader("Content-Disposition", "attachment; filename=\"" + filenamePrefix + ld.getYear() + ld.getMonthOfYear() + 
				ld.getDayOfMonth() + ".csv\"");
		
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
