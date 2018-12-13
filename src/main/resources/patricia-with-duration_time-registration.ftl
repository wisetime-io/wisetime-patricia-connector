<#if narrative?has_content>${narrative}</#if>
<#if !excludeSegmentDetails>
 <#list dayActivityItems as activityItem>
${activityItem.firstTimeRecordal?string("HH:mm")} hrs - [${activityItem.durationSecs?string.@duration}] - ${activityItem.activityType} - ${activityItem.activityDescription}
 </#list>
</#if>