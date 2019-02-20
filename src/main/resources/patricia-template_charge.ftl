<#include "helper-functions.ftl">
<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
<#list getSegmentHourBlocks(getTimeRows())?sort as segmentHourBlock>
${'\r\n'}${segmentHourBlock}:00 - ${segmentHourBlock}:59
 <#list getTimeRows() as timeRow>
  <#assign timeRowSegmentHour = (timeRow.getActivityHour() % 100)?string["00"]>
  <#if segmentHourBlock == timeRowSegmentHour>
- ${timeRow.getDurationSecs()?string.@duration} - ${sanitizeAppName(timeRow.getActivity())} - ${sanitizeWindowTitle(timeRow.getDescription()!"@_empty_@")}
  </#if>
 </#list>
</#list>
</#if>
${'\r\n'}Total Worked Time: ${getTotalDuration(getTimeRows())?string.@duration}
Total Chargeable Time: ${getTotalDurationSecs()?string.@duration}
Experience Weighting: ${getUser().getExperienceWeightingPercent()}%
<#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
 ${'\r\n'}The above times have been split across ${getTags()?size} cases and are thus greater than the chargeable time in this case
</#if>

