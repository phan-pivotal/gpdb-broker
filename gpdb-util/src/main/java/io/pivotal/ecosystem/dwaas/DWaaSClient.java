/*
 * Copyright (C) 2016-Present Pivotal Software, Inc. All rights reserved.
 * <p>
 * This program and the accompanying materials are made available under
 * the terms of the under the Apache License, Version 2.0 (the "License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pivotal.ecosystem.dwaas;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerException;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.pivotal.ecosystem.dwaas.connector.DWaaSServiceInfo;
import io.pivotal.ecosystem.servicebroker.model.ServiceBinding;

@Repository
class DWaaSClient {
    private static final Logger log = LoggerFactory.getLogger(DWaaSClient.class);
    private JdbcTemplate jdbcTemplate;
    private String url;
    private boolean isUserProvided = false;

    public DWaaSClient(DataSource dataSource, Environment env) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.url = env.getProperty("spring.datasource.url");
        
    }

    boolean checkDatabaseExists(String db) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM pg_database WHERE datname = ?", new Object[]{db}, Integer.class) > 0;
    }

    String getDbUrl(String user, String dbName, String passwd) {
        //"jdbc:pivotal:greenplum://104.198.46.128:5432;DatabaseName=gpadmin;"
        String connectionString = url.replace("gpadmin", "gpadmin")
                + ";User=" + user
                + ";Password=" + passwd
                + ";";

        return connectionString;
    }

    //todo how to protect dbs etc. from bad actors?
    private String getRandomishId() {
        return clean(UUID.randomUUID().toString());
    }

    /**
     * jdbcTemplate helps protect against sql injection, but also clean strings up just in case
     */
    String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^a-zA-Z0-9]", "");
    }

    private String checkString(String s) throws ServiceBrokerException {
        if (s.equals(clean(s))) {
            return s;
        }
        throw new ServiceBrokerException("Name must contain only alphanumeric characters.");
    }

    private String createUserId(Object o) {
        if (o != null) {
            return checkString(o.toString());
        }
        return "u" + getRandomishId();
    }

    private String createPassword(Object o) {
        if (o != null) {
            return checkString(o.toString());
        }
        return "P" + getRandomishId();
    }

    Map<String, String> createUserCreds(ServiceBinding binding) throws Exception {
        log.debug("Inside createUserCred()");

        Map<String, String> userCredentials = new HashMap<>();
        String userCredential;
        String passCredential;
        String providedDatabase;


        if (binding.getParameters().get(DWaaSServiceInfo.USERNAME) == null) {
            log.info("Created random credentials");

            userCredential = createUserId(null);
            passCredential = createPassword(null);
            providedDatabase = (String) binding.getParameters().get(DWaaSServiceInfo.DATABASE);
            if (providedDatabase != null) {

                userCredentials.put(DWaaSServiceInfo.USERNAME, userCredential);
                userCredentials.put(DWaaSServiceInfo.PASSWORD, passCredential);
                userCredentials.put(DWaaSServiceInfo.DATABASE, providedDatabase);

                jdbcTemplate.execute("CREATE ROLE "
                        + userCredentials.get(DWaaSServiceInfo.USERNAME)
                        + " LOGIN SUPERUSER PASSWORD '"
                        + userCredentials.get(DWaaSServiceInfo.PASSWORD)
                        + "'");

                log.info("Created user: " + userCredentials.get(DWaaSServiceInfo.USERNAME));
            } else {
                log.error("You must pass a Database Name on Binding");
                throw new Exception();
            }


        } else

        {
            log.info("Populating with provided credentials");
            userCredential = (String) binding.getParameters().get(DWaaSServiceInfo.USERNAME);
            passCredential = (String) binding.getParameters().get(DWaaSServiceInfo.PASSWORD);
            providedDatabase = (String) binding.getParameters().get(DWaaSServiceInfo.DATABASE);

            userCredentials.put(DWaaSServiceInfo.USERNAME, userCredential);
            userCredentials.put(DWaaSServiceInfo.PASSWORD, passCredential);
            userCredentials.put(DWaaSServiceInfo.DATABASE, providedDatabase);

            this.isUserProvided = true;
            log.info("Bind Request [Credentials Provided: {} No New ROLE CREATED]", userCredential.toString());

        }

        return userCredentials;
    }


    void deleteUserCreds(Map userCredentials) {
        String uid;
        if (isUserProvided == false) {
            uid = userCredentials.get(DWaaSServiceInfo.USERNAME).toString();
            jdbcTemplate.execute("DROP ROLE IF EXISTS " + uid);
        }
    }

    boolean checkUserExists(String uid) {
        return jdbcTemplate.queryForObject("select count(*) from pg_roles where rolname = ?", new Object[]{uid}, Integer.class) == 1;
    }
}
