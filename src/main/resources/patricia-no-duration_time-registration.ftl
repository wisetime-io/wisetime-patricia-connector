<#if narrative?has_content>${narrative}</#if>
<#if !excludeSegmentDetails>
 <#list dayActivityItems as activityItem>
${activityItem.firstTimeRecordal?string("HH:mm")} - ${activityItem.activityType} - ${activityItem.activityDescription}
 </#list>
</#if>