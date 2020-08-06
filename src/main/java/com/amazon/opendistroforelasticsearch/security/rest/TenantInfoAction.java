/*
 * Copyright 2015-2018 _floragunn_ GmbH
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
 */

/*
 * Portions Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.security.rest;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.SortedMap;

import com.amazon.opendistroforelasticsearch.security.configuration.IndexBaseConfigurationRepository;
import com.amazon.opendistroforelasticsearch.security.dlic.rest.support.Utils;
import com.google.common.base.Strings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import com.amazon.opendistroforelasticsearch.security.configuration.AdminDNs;
import com.amazon.opendistroforelasticsearch.security.privileges.PrivilegesEvaluator;
import com.amazon.opendistroforelasticsearch.security.support.ConfigConstants;
import com.amazon.opendistroforelasticsearch.security.user.User;

public class TenantInfoAction extends BaseRestHandler {

    private final Logger log = LogManager.getLogger(this.getClass());
    private final PrivilegesEvaluator evaluator;
    private final ThreadContext threadContext;
    private final ClusterService clusterService;
    private final AdminDNs adminDns;
    protected final IndexBaseConfigurationRepository configurationRepository;

    public TenantInfoAction(final Settings settings, final RestController controller, 
            final PrivilegesEvaluator evaluator, final ThreadPool threadPool, final ClusterService clusterService, final AdminDNs adminDns,
                            final IndexBaseConfigurationRepository configurationRepository) {
        super(settings);
        this.threadContext = threadPool.getThreadContext();
        this.evaluator = evaluator;
        this.clusterService = clusterService;
        this.adminDns = adminDns;
        controller.registerHandler(GET, "/_opendistro/_security/tenantinfo", this);
        controller.registerHandler(POST, "/_opendistro/_security/tenantinfo", this);
        this.configurationRepository = configurationRepository;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        return new RestChannelConsumer() {

            @Override
            public void accept(RestChannel channel) throws Exception {
                XContentBuilder builder = channel.newBuilder(); //NOSONAR
                BytesRestResponse response = null;
                
                try {

                    final User user = (User)threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_USER);
                    
                    //only allowed for admins or the kibanaserveruser
                    if(!isAuthorized()) {
                        response = new BytesRestResponse(RestStatus.FORBIDDEN,"");
                    } else {

                    	builder.startObject();
	
                    	final SortedMap<String, AliasOrIndex> lookup = clusterService.state().metaData().getAliasAndIndexLookup();
                    	for(final String indexOrAlias: lookup.keySet()) {
                    		final String tenant = tenantNameForIndex(indexOrAlias);
                    		if(tenant != null) {
                    			builder.field(indexOrAlias, tenant);
                    		}
                    	}

	                    builder.endObject();
	
	                    response = new BytesRestResponse(RestStatus.OK, builder);
                    }
                } catch (final Exception e1) {
                    log.error(e1.toString(),e1);
                    builder = channel.newBuilder(); //NOSONAR
                    builder.startObject();
                    builder.field("error", e1.toString());
                    builder.endObject();
                    response = new BytesRestResponse(RestStatus.INTERNAL_SERVER_ERROR, builder);
                } finally {
                    if(builder != null) {
                        builder.close();
                    }
                }

                channel.sendResponse(response);
            }
        };
    }

    private boolean isAuthorized() {
        final User user = (User)threadContext.getTransient(ConfigConstants.OPENDISTRO_SECURITY_USER);

        if (user == null) {
            return false;
        }

        // check if the user is a kibanauser or super admin
        if (user.getName().equals(evaluator.kibanaServerUsername()) || adminDns.isAdmin(user)) {
            return true;
        }

        // If user check failed by name and admin, check if the users belong to kibana opendistro role
        final Tuple<Long, Settings.Builder> rolesMapping = load(ConfigConstants.CONFIGNAME_ROLES_MAPPING, true);
        final Map<String, Object> rolesMappingConfiguration = Utils.convertJsonToxToStructuredMap(rolesMapping.v2().build());

        // check if kibanaOpendistroRole is present in RolesMapping and if yes, check if user is a part of this role
        if (rolesMappingConfiguration != null) {
            String kibanaOpendistroRole = evaluator.kibanaOpendistroRole();
            if (Strings.isNullOrEmpty(kibanaOpendistroRole)) {
                return false;
            }

            Map<String, Object> kibanaOpendistroRoleEntry = (Map<String, Object>) rolesMappingConfiguration.getOrDefault(kibanaOpendistroRole, null);

            if (kibanaOpendistroRoleEntry != null) {
                ArrayList<String> kibanaOpendistroRoleUsers = (ArrayList<String>) kibanaOpendistroRoleEntry.get("users");
                return kibanaOpendistroRoleUsers != null && kibanaOpendistroRoleUsers.contains(user.getName());
            }
        }

        return false;
    }

    protected final Tuple<Long, Settings.Builder> load(final String config, boolean logComplianceEvent) {
        Tuple<Long, Settings> t = configurationRepository.loadConfigurations(Collections.singleton(config), logComplianceEvent).get(config);
        return new Tuple<Long, Settings.Builder>(t.v1(), Settings.builder().put(t.v2()));
    }

    private String tenantNameForIndex(String index) {
    	String[] indexParts;
    	if(index == null 
    			|| (indexParts = index.split("_")).length != 3
    			) {
    		return null;
    	}
    	
    	
    	if(!indexParts[0].equals(evaluator.kibanaIndex())) {
    		return null;
    	}
    	
    	try {
			final int expectedHash = Integer.parseInt(indexParts[1]);
			final String sanitizedName = indexParts[2];
			
			for(String tenant: evaluator.getAllConfiguredTenantNames()) {
				if(tenant.hashCode() == expectedHash && sanitizedName.equals(tenant.toLowerCase().replaceAll("[^a-z0-9]+",""))) {
					return tenant;
				}
			}

			return "__private__";
		} catch (NumberFormatException e) {
			log.warn("Index "+index+" looks like a Security tenant index but we cannot parse the hashcode so we ignore it.");
			return null;
		}
    }

    @Override
    public String getName() {
        return "Tenant Info Action";
    }
    
    
}
