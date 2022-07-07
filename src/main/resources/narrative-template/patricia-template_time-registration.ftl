<#include "narrative-and-time-row-info.ftl">
${'\r\n'}Total Worked Time: ${getTotalDuration(getTimeRows())?string.@duration}
<#if getTotalDuration(getTimeRows()) == getTotalDurationSecs() && getUser().getExperienceWeightingPercent() != 100>
Total Chargeable Time: ${(getTotalDurationSecs() * getUser().getExperienceWeightingPercent() / 100)?round?string.@duration}
${'\r\n'}The chargeable time has been weighed based on an experience factor of ${getUser().getExperienceWeightingPercent()}%.
<#else>
Total Chargeable Time: ${getTotalDurationSecs()?string.@duration}
</#if>