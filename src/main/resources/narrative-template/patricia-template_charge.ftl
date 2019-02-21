<#include "narrative-and-time-row-info.ftl">
${'\r\n'}Total Worked Time: ${getTotalDuration(getTimeRows())?string.@duration}
Total Chargeable Time: ${getTotalDurationSecs()?string.@duration}
Experience Weighting: ${getUser().getExperienceWeightingPercent()}%
<#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
 ${'\r\n'}The above times have been split across ${getTags()?size} cases and are thus greater than the chargeable time in this case
</#if>

