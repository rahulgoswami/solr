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
package org.apache.solr.search.grouping.distributed.responseprocessor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.handler.component.HttpShardHandler;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.handler.component.ShardResponse;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.grouping.distributed.ShardResponseProcessor;
import org.apache.solr.search.grouping.distributed.command.SearchGroupsFieldCommandResult;
import org.apache.solr.search.grouping.distributed.shardresultserializer.SearchGroupsResultTransformer;
import org.apache.solr.util.SolrResponseUtil;

/** Concrete implementation for merging {@link SearchGroup} instances from shard responses. */
public class SearchGroupShardResponseProcessor implements ShardResponseProcessor {

  @Override
  public void process(ResponseBuilder rb, ShardRequest shardRequest) {
    SortSpec groupSortSpec = rb.getGroupingSpec().getGroupSortSpec();
    Sort groupSort = rb.getGroupingSpec().getGroupSortSpec().getSort();
    final String[] fields = rb.getGroupingSpec().getFields();
    Sort withinGroupSort = rb.getGroupingSpec().getWithinGroupSortSpec().getSort();
    assert withinGroupSort != null;

    final Map<String, List<Collection<SearchGroup<BytesRef>>>> commandSearchGroups =
        new HashMap<>(fields.length, 1.0f);
    final Map<String, Map<SearchGroup<BytesRef>, Set<String>>> tempSearchGroupToShards =
        new HashMap<>(fields.length, 1.0f);
    for (String field : fields) {
      commandSearchGroups.put(field, new ArrayList<>(shardRequest.responses.size()));
      tempSearchGroupToShards.put(field, new HashMap<>());
      if (!rb.searchGroupToShards.containsKey(field)) {
        rb.searchGroupToShards.put(field, new HashMap<>());
      }
    }

    SearchGroupsResultTransformer serializer =
        new SearchGroupsResultTransformer(rb.req.getSearcher());
    long maxElapsedTime = 0;
    int hitCountDuringFirstPhase = 0;

    NamedList<Object> shardInfo = null;
    if (rb.req.getParams().getBool(ShardParams.SHARDS_INFO, false)) {
      shardInfo = new SimpleOrderedMap<>(shardRequest.responses.size());
      rb.rsp.getValues().add(ShardParams.SHARDS_INFO + ".firstPhase", shardInfo);
    }

    for (ShardResponse srsp : shardRequest.responses) {
      SolrResponse solrResponse = srsp.getSolrResponse();
      NamedList<?> response = solrResponse.getResponse();
      if (shardInfo != null) {
        SimpleOrderedMap<Object> nl = new SimpleOrderedMap<>(4);

        if (srsp.getException() != null) {
          Throwable t = srsp.getException();
          if (t instanceof SolrServerException) {
            t = t.getCause();
          }
          nl.add("error", t.toString());
          if (!rb.req.getCore().getCoreContainer().hideStackTrace()) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            nl.add("trace", trace.toString());
          }
        } else {
          nl.add("numFound", response.get("totalHitCount"));
        }

        nl.add("time", solrResponse.getElapsedTime());

        if (srsp.getShardAddress() != null) {
          nl.add("shardAddress", srsp.getShardAddress());
        }
        shardInfo.add(srsp.getShard(), nl);
      }
      if (HttpShardHandler.getShardsTolerantAsBool(rb.req) && srsp.getException() != null) {
        rb.rsp.setPartialResults(rb.req);
        continue; // continue if there was an error and we're tolerant.
      }
      maxElapsedTime = Math.max(maxElapsedTime, solrResponse.getElapsedTime());
      @SuppressWarnings("unchecked")
      NamedList<NamedList<?>> firstPhaseResult =
          (NamedList<NamedList<?>>)
              SolrResponseUtil.getSubsectionFromShardResponse(rb, srsp, "firstPhase", false);
      if (firstPhaseResult == null) {
        continue; // looks like a shard did not return anything
      }
      final Map<String, SearchGroupsFieldCommandResult> result =
          serializer.transformToNative(
              firstPhaseResult, groupSort, withinGroupSort, srsp.getShard());
      for (Map.Entry<String, List<Collection<SearchGroup<BytesRef>>>> entry :
          commandSearchGroups.entrySet()) {
        String field = entry.getKey();
        final SearchGroupsFieldCommandResult firstPhaseCommandResult = result.get(field);

        final Integer groupCount = firstPhaseCommandResult.getGroupCount();
        if (groupCount != null) {
          Integer existingGroupCount = rb.mergedGroupCounts.get(field);
          // Assuming groups don't cross shard boundary...
          rb.mergedGroupCounts.put(
              field,
              existingGroupCount != null
                  ? Integer.valueOf(existingGroupCount + groupCount)
                  : groupCount);
        }

        final Collection<SearchGroup<BytesRef>> searchGroups =
            firstPhaseCommandResult.getSearchGroups();
        if (searchGroups == null) {
          continue;
        }

        entry.getValue().add(searchGroups);
        for (SearchGroup<BytesRef> searchGroup : searchGroups) {
          Map<SearchGroup<BytesRef>, Set<String>> map = tempSearchGroupToShards.get(field);
          Set<String> shards = map.computeIfAbsent(searchGroup, k -> new HashSet<>());
          shards.add(srsp.getShard());
        }
      }
      hitCountDuringFirstPhase += (Integer) response.get("totalHitCount");
    }
    rb.totalHitCount = hitCountDuringFirstPhase;
    rb.firstPhaseElapsedTime = (int) maxElapsedTime;
    for (Map.Entry<String, List<Collection<SearchGroup<BytesRef>>>> entry :
        commandSearchGroups.entrySet()) {
      String groupField = entry.getKey();
      List<Collection<SearchGroup<BytesRef>>> topGroups = entry.getValue();
      Collection<SearchGroup<BytesRef>> mergedTopGroups =
          SearchGroup.merge(
              topGroups, groupSortSpec.getOffset(), groupSortSpec.getCount(), groupSort);
      if (mergedTopGroups == null) {
        continue;
      }

      rb.mergedSearchGroups.put(groupField, mergedTopGroups);
      for (SearchGroup<BytesRef> mergedTopGroup : mergedTopGroups) {
        rb.searchGroupToShards
            .get(groupField)
            .put(mergedTopGroup, tempSearchGroupToShards.get(groupField).get(mergedTopGroup));
      }
    }
  }
}
