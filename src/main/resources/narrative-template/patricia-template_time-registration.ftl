<#include "narrative-and-time-row-info.ftl">
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
${'\r\n'}Total Worked Time: ${getTotalDuration(getTimeRows())?string.@duration}
Total Chargeable Time: ${getTotalDurationSecs()?string.@duration}
<#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
    ${'\r\n'}The above times have been split across ${getTags()?size} cases and are thus greater than the chargeable time in this case
</#if>
</#if>