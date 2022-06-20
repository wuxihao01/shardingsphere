/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.text.distsql.ral.common.updatable;

import org.apache.shardingsphere.distsql.parser.segment.TrafficRuleSegment;
import org.apache.shardingsphere.distsql.parser.statement.ral.common.updatable.AlterTrafficRuleStatement;
import org.apache.shardingsphere.infra.config.algorithm.ShardingSphereAlgorithmConfiguration;
import org.apache.shardingsphere.infra.distsql.exception.DistSQLException;
import org.apache.shardingsphere.infra.distsql.exception.rule.InvalidAlgorithmConfigurationException;
import org.apache.shardingsphere.infra.distsql.exception.rule.RequiredRuleMissedException;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.text.distsql.ral.UpdatableRALBackendHandler;
import org.apache.shardingsphere.proxy.backend.text.distsql.ral.common.convert.TrafficRuleConverter;
import org.apache.shardingsphere.traffic.api.config.TrafficRuleConfiguration;
import org.apache.shardingsphere.traffic.api.config.TrafficStrategyConfiguration;
import org.apache.shardingsphere.traffic.factory.TrafficAlgorithmFactory;
import org.apache.shardingsphere.traffic.factory.TrafficLoadBalanceAlgorithmFactory;
import org.apache.shardingsphere.traffic.rule.TrafficRule;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alter traffic rule handler.
 */
public final class AlterTrafficRuleHandler extends UpdatableRALBackendHandler<AlterTrafficRuleStatement> {
    
    @Override
    protected void update(final ContextManager contextManager) throws DistSQLException {
        check();
        replaceNewRule(contextManager);
        persistNewRuleConfigurations();
    }
    
    private void check() throws DistSQLException {
        checkRuleNames();
        checkAlgorithmNames();
    }
    
    private void checkRuleNames() throws DistSQLException {
        TrafficRuleConfiguration currentConfig = ProxyContext
                .getInstance().getContextManager().getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getSingleRule(TrafficRule.class).getConfiguration();
        Collection<String> currentRuleNames = currentConfig.getTrafficStrategies().stream().map(TrafficStrategyConfiguration::getName).collect(Collectors.toSet());
        Set<String> notExistRuleNames = getSqlStatement().getSegments().stream().map(TrafficRuleSegment::getName).filter(each -> !currentRuleNames.contains(each)).collect(Collectors.toSet());
        DistSQLException.predictionThrow(notExistRuleNames.isEmpty(), () -> new RequiredRuleMissedException("Traffic", notExistRuleNames));
    }
    
    private void checkAlgorithmNames() throws DistSQLException {
        Collection<String> invalidAlgorithmNames = getInvalidAlgorithmNames();
        DistSQLException.predictionThrow(invalidAlgorithmNames.isEmpty(), () -> new InvalidAlgorithmConfigurationException("Traffic", invalidAlgorithmNames));
    }
    
    private Collection<String> getInvalidAlgorithmNames() {
        Collection<String> result = new LinkedList<>();
        for (TrafficRuleSegment each : getSqlStatement().getSegments()) {
            if (!TrafficAlgorithmFactory.contains(each.getAlgorithm().getName())) {
                result.add(each.getAlgorithm().getName());
            }
            if (null != each.getLoadBalancer() && !TrafficLoadBalanceAlgorithmFactory.contains(each.getLoadBalancer().getName())) {
                result.add(each.getLoadBalancer().getName());
            }
        }
        return result;
    }
    
    private void replaceNewRule(final ContextManager contextManager) {
        TrafficRuleConfiguration toBeAlteredRuleConfig = createToBeAlteredRuleConfiguration();
        Collection<ShardingSphereRule> globalRules = ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getRules();
        globalRules.removeIf(each -> each instanceof TrafficRule);
        globalRules.add(new TrafficRule(toBeAlteredRuleConfig));
        // TODO remove me after ShardingSphereRuleMetaData.configuration removed
        contextManager.getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getConfigurations().removeIf(each -> each instanceof TrafficRuleConfiguration);
        contextManager.getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getConfigurations().add(toBeAlteredRuleConfig);
    }
    
    private TrafficRuleConfiguration createToBeAlteredRuleConfiguration() {
        TrafficRuleConfiguration result = new TrafficRuleConfiguration();
        TrafficRuleConfiguration configFromSQLStatement = TrafficRuleConverter.convert(getSqlStatement().getSegments());
        TrafficRuleConfiguration currentConfig = ProxyContext
                .getInstance().getContextManager().getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getSingleRule(TrafficRule.class).getConfiguration();
        result.getTrafficStrategies().addAll(createToBeAlteredStrategyConfigurations(currentConfig, configFromSQLStatement));
        result.getTrafficAlgorithms().putAll(createToBeAlteredTrafficAlgorithms(currentConfig, configFromSQLStatement));
        result.getLoadBalancers().putAll(createToBeAlteredLoadBalancers(currentConfig, configFromSQLStatement, getInUsedLoadBalancer(result)));
        return result;
    }
    
    private Collection<TrafficStrategyConfiguration> createToBeAlteredStrategyConfigurations(final TrafficRuleConfiguration currentConfig, final TrafficRuleConfiguration configFromSQLStatement) {
        Collection<TrafficStrategyConfiguration> result = new LinkedList<>(currentConfig.getTrafficStrategies());
        Collection<String> toBeAlteredConfigNames = configFromSQLStatement.getTrafficStrategies().stream().map(TrafficStrategyConfiguration::getName).collect(Collectors.toSet());
        result.removeIf(each -> toBeAlteredConfigNames.contains(each.getName()));
        result.addAll(configFromSQLStatement.getTrafficStrategies());
        return result;
    }
    
    private Map<String, ShardingSphereAlgorithmConfiguration> createToBeAlteredTrafficAlgorithms(final TrafficRuleConfiguration currentConfig, final TrafficRuleConfiguration configFromSQLStatement) {
        Map<String, ShardingSphereAlgorithmConfiguration> result = new LinkedHashMap<>(currentConfig.getTrafficAlgorithms());
        result.putAll(configFromSQLStatement.getTrafficAlgorithms());
        return result;
    }
    
    private Collection<String> getInUsedLoadBalancer(final TrafficRuleConfiguration config) {
        return config.getTrafficStrategies().stream().map(TrafficStrategyConfiguration::getLoadBalancerName).collect(Collectors.toSet());
    }
    
    private Map<String, ShardingSphereAlgorithmConfiguration> createToBeAlteredLoadBalancers(final TrafficRuleConfiguration currentConfig,
                                                                                             final TrafficRuleConfiguration configFromSQLStatement, final Collection<String> inUsedLoadBalancer) {
        Map<String, ShardingSphereAlgorithmConfiguration> result = new LinkedHashMap<>(currentConfig.getLoadBalancers());
        result.putAll(configFromSQLStatement.getLoadBalancers());
        for (String each : result.keySet().stream().filter(each -> !inUsedLoadBalancer.contains(each)).collect(Collectors.toSet())) {
            result.remove(each);
        }
        return result;
    }
    
    private void persistNewRuleConfigurations() {
        MetaDataContexts metaDataContexts = ProxyContext.getInstance().getContextManager().getMetaDataContexts();
        metaDataContexts.getPersistService().ifPresent(optional -> optional.getGlobalRuleService().persist(metaDataContexts.getMetaData().getGlobalRuleMetaData().getConfigurations(), true));
    }
}
