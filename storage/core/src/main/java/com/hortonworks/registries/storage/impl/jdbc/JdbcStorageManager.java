/**
 * Copyright 2016 Hortonworks.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.hortonworks.registries.storage.impl.jdbc;


import com.hortonworks.registries.common.QueryParam;
import com.hortonworks.registries.common.Schema;
import com.hortonworks.registries.storage.PrimaryKey;
import com.hortonworks.registries.storage.Storable;
import com.hortonworks.registries.storage.StorableFactory;
import com.hortonworks.registries.storage.StorableKey;
import com.hortonworks.registries.storage.StorageManager;
import com.hortonworks.registries.storage.exception.AlreadyExistsException;
import com.hortonworks.registries.storage.exception.IllegalQueryParameterException;
import com.hortonworks.registries.storage.exception.StorageException;
import com.hortonworks.registries.storage.impl.jdbc.provider.mysql.factory.MySqlExecutor;
import com.hortonworks.registries.storage.impl.jdbc.provider.phoenix.factory.PhoenixExecutor;
import com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.factory.PostgresqlExecutor;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.factory.QueryExecutor;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.query.MetadataHelper;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.query.OrderByField;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.query.SqlSelectQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//Use unique constraints on respective columns of a table for handling concurrent inserts etc.
public class JdbcStorageManager implements StorageManager {
    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);
    public static final String DB_TYPE = "db.type";
    public static final String ORDER_BY_FIELDS_PARAM_NAME = "_orderByFields";

    private final StorableFactory storableFactory = new StorableFactory();
    private QueryExecutor queryExecutor;

    public JdbcStorageManager() {
    }

    public JdbcStorageManager(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
        queryExecutor.setStorableFactory(storableFactory);
    }

    @Override
    public void add(Storable storable) throws AlreadyExistsException {
        log.debug("Adding storable [{}]", storable);
        queryExecutor.insert(storable);
    }

    @Override
    public <T extends Storable> T remove(StorableKey key) throws StorageException {
        T oldVal = get(key);
        if (key != null) {
            log.debug("Removing storable key [{}]", key);
            queryExecutor.delete(key);
        }
        return oldVal;
    }

    @Override
    public void addOrUpdate(Storable storable) throws StorageException {
        log.debug("Adding or updating storable [{}]", storable);
        queryExecutor.insertOrUpdate(storable);
    }

    @Override
    public <T extends Storable> T get(StorableKey key) throws StorageException {
        log.debug("Searching entry for storable key [{}]", key);

        final Collection<T> entries = queryExecutor.select(key);
        T entry = null;
        if (entries.size() > 0) {
            if (entries.size() > 1) {
                log.debug("More than one entry found for storable key [{}]", key);
            }
            entry = entries.iterator().next();
        }
        log.debug("Querying key = [{}]\n\t returned [{}]", key, entry);
        return entry;
    }

    @Override
    public <T extends Storable> Collection<T> find(String namespace, List<QueryParam> queryParams)
            throws StorageException {
        log.debug("Searching for entries in table [{}] that match queryParams [{}]", namespace, queryParams);

        List<QueryParam> filteredQueryParams =
                queryParams != null
                        ? queryParams.stream().filter(param -> !ORDER_BY_FIELDS_PARAM_NAME.equals(param.getName()))
                        .collect(Collectors.toList())
                        : Collections.emptyList();
        List<OrderByField> orderByFields = getOrderByFields(queryParams);

        if (filteredQueryParams.isEmpty()) {
            return list(namespace, orderByFields);
        }

        Collection<T> entries = Collections.emptyList();
        try {
            StorableKey storableKey = buildStorableKey(namespace, filteredQueryParams);
            if (storableKey != null) {
                entries = queryExecutor.select(storableKey, orderByFields);
            }
        } catch (Exception e) {
            throw new StorageException(e);
        }

        log.debug("Querying table = [{}]\n\t filter = [{}]\n\t returned [{}]", namespace, queryParams, entries);

        return entries;
    }

    private <T extends Storable> Collection<T> list(String namespace, List<OrderByField> orderByFields) {
        log.debug("Listing entries for table [{}]", namespace);
        final Collection<T> entries = queryExecutor.select(namespace, orderByFields);
        log.debug("Querying table = [{}]\n\t returned [{}]", namespace, entries);
        return entries;
    }

    private List<OrderByField> getOrderByFields(List<QueryParam> queryParams) {
        if(queryParams == null || queryParams.isEmpty()) {
            return Collections.emptyList();
        }

        List<OrderByField> orderByFields = new ArrayList<>();
        for (QueryParam queryParam : queryParams) {
            if (ORDER_BY_FIELDS_PARAM_NAME.equals(queryParam.getName())) {
                // _orderByFields=[<field-name>,<a/d>,]*
                // example can be : _orderByFields=foo,a,bar,d
                // order by foo with ascending then bar with descending
                String value = queryParam.getValue();
                String[] splitStrings = value.split(",");
                for (int i = 0; i < splitStrings.length; i += 2) {
                    String ascStr = splitStrings[i+1];
                    boolean descending;
                    if("a".equals(ascStr)) {
                        descending = false;
                    } else if("d".equals(ascStr)) {
                        descending = true;
                    } else {
                        throw new IllegalArgumentException("Ascending or Descending identifier can only be 'a' or 'd' respectively.");
                    }

                    orderByFields.add(OrderByField.of(splitStrings[i], descending));
                }
            }
        }

        return orderByFields;
    }

    @Override
    public <T extends Storable> Collection<T> list(String namespace) throws StorageException {
        return list(namespace, Collections.emptyList());
    }

    @Override
    public void cleanup() throws StorageException {
        queryExecutor.cleanup();
    }

    @Override
    public Long nextId(String namespace) {
        log.debug("Finding nextId for table [{}]", namespace);
        // This only works if the table has auto-increment. The TABLE_SCHEMA part is implicitly specified in the Connection object
        // SELECT AUTO_INCREMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'temp' AND TABLE_SCHEMA = 'test'
        return queryExecutor.nextId(namespace);
    }

    @Override
    public void registerStorables(Collection<Class<? extends Storable>> classes) throws StorageException {
        storableFactory.addStorableClasses(classes);
    }

    // private helper methods

    /**
     * Query parameters are typically specified for a column or key in a database table or storage namespace. Therefore, we build
     * the {@link StorableKey} from the list of query parameters, and then can use {@link SqlSelectQuery} builder to generate the query using
     * the query parameters in the where clause
     *
     * @return {@link StorableKey} with all query parameters that match database columns <br>
     * null if none of the query parameters specified matches a column in the DB
     */
    private StorableKey buildStorableKey(String namespace, List<QueryParam> queryParams) throws Exception {
        final Map<Schema.Field, Object> fieldsToVal = new HashMap<>();
        final Connection connection = queryExecutor.getConnection();
        StorableKey storableKey = null;

        try {
            for (QueryParam qp : queryParams) {
                int queryTimeoutSecs = queryExecutor.getConfig().getQueryTimeoutSecs();
                if (!MetadataHelper.isColumnInNamespace(connection, queryTimeoutSecs, namespace, qp.getName())) {
                    log.warn("Query parameter [{}] does not exist for namespace [{}]. Query parameter ignored.", qp.getName(), namespace);
                } else {
                    final String val = qp.getValue();
                    final Schema.Type typeOfVal = Schema.Type.getTypeOfVal(val);
                    fieldsToVal.put(new Schema.Field(qp.getName(), typeOfVal),
                        typeOfVal.getJavaType().getConstructor(String.class).newInstance(val)); // instantiates object of the appropriate type
                }
            }

            // it is empty when none of the query parameters specified matches a column in the DB
            if (!fieldsToVal.isEmpty()) {
                final PrimaryKey primaryKey = new PrimaryKey(fieldsToVal);
                storableKey = new StorableKey(namespace, primaryKey);
            }

            log.debug("Building StorableKey from QueryParam: \n\tnamespace = [{}]\n\t queryParams = [{}]\n\t StorableKey = [{}]",
                    namespace, queryParams, storableKey);
        } catch (Exception e) {
            log.debug("Exception occurred when attempting to generate StorableKey from QueryParam", e);
            throw new IllegalQueryParameterException(e);
        } finally {
            queryExecutor.closeConnection(connection);
        }

        return storableKey;
    }

    /**
     * Initializes this instance with {@link QueryExecutor} created from the given {@code properties}.
     * Some of these properties are jdbcDriverClass, jdbcUrl, queryTimeoutInSecs.
     *
     * @param properties properties with name/value pairs
     */
    @Override
    public void init(Map<String, Object> properties) {

        if(!properties.containsKey(DB_TYPE)) {
            throw new IllegalArgumentException("db.type should be set on jdbc properties");
        }

        String type = (String) properties.get(DB_TYPE);

        // When we have more providers we can add a layer to have a factory to create respective jdbc storage managers.
        // For now, keeping it simple as there are only 2.
        if(!"phoenix".equals(type) && !"mysql".equals(type) && !"postgresql".equals(type)) {
            throw new IllegalArgumentException("Unknown jdbc storage provider type: "+type);
        }
        log.info("jdbc provider type: [{}]", type);
        Map<String, Object> dbProperties = (Map<String, Object>) properties.get("db.properties");

        QueryExecutor queryExecutor;
        switch (type) {
            case "phoenix":
                try {
                    queryExecutor = PhoenixExecutor.createExecutor(dbProperties);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                break;
            case "mysql":
                queryExecutor = MySqlExecutor.createExecutor(dbProperties);
                break;
            case "postgresql":
                queryExecutor = PostgresqlExecutor.createExecutor(dbProperties);
                break;
            default:
                throw new IllegalArgumentException("Unsupported storage provider type: "+type);
        }

        this.queryExecutor = queryExecutor;
        this.queryExecutor.setStorableFactory(storableFactory);
    }

}
