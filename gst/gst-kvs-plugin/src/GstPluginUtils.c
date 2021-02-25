#define LOG_CLASS "GstPluginUtils"
#include "GstPlugin.h"

gboolean setGstTags(GQuark fieldId, const GValue* value, gpointer userData)
{
    STATUS retStatus = STATUS_SUCCESS;
    PGstTag pGstTag = (PGstTag) userData;
    PTag pTag;

    CHK_ERR(pGstTag != NULL, STATUS_NULL_ARG, "Iterator supplies NULL user data");
    CHK_ERR(pGstTag->tagCount < MAX_TAG_COUNT, STATUS_INVALID_ARG_LEN, "Max tag count reached");
    CHK_ERR(G_VALUE_HOLDS_STRING(value), STATUS_INVALID_ARG, "Tag value should be of a string type");

    pTag = &pGstTag->tags[pGstTag->tagCount];
    pTag->version = TAG_CURRENT_VERSION;
    STRNCPY(pTag->name, g_quark_to_string(fieldId), MAX_TAG_NAME_LEN);
    STRNCPY(pTag->value, g_value_get_string(value), MAX_TAG_VALUE_LEN);

    pGstTag->tagCount++;

CleanUp:

    CHK_LOG_ERR(retStatus);

    return STATUS_SUCCEEDED(retStatus);
}

STATUS gstStructToTags(GstStructure* pGstStruct, PGstTag pGstTag)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pGstTag != NULL, STATUS_NULL_ARG);

    MEMSET(pGstTag, 0x00, SIZEOF(GstTags));

    // Tags are optional
    CHK(pGstStruct != NULL, retStatus);

    // Iterate each field and process the tags
    CHK(gst_structure_foreach(pGstStruct, setGstTags, pGstTag), STATUS_INVALID_ARG);

CleanUp:

    return retStatus;
}

gboolean setGstIotInfo(GQuark fieldId, const GValue* value, gpointer userData)
{
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pFieldName;
    PIotInfo pIotInfo = (PIotInfo) userData;

    CHK_ERR(pIotInfo != NULL, STATUS_NULL_ARG, "Iterator supplies NULL user data");
    CHK_ERR(G_VALUE_HOLDS_STRING(value), STATUS_INVALID_ARG, "Tag value should be of a string type");

    pFieldName = (PCHAR) g_quark_to_string(fieldId);

    if (0 == STRCMP(pFieldName, IOT_GET_CREDENTIAL_ENDPOINT)) {
        STRNCPY(pIotInfo->endPoint, g_value_get_string(value), MAX_URI_CHAR_LEN);
    } else if (0 == STRCMP(pFieldName, CERTIFICATE_PATH)) {
        STRNCPY(pIotInfo->certPath, g_value_get_string(value), MAX_PATH_LEN);
    } else if (0 == STRCMP(pFieldName, PRIVATE_KEY_PATH)) {
        STRNCPY(pIotInfo->privateKeyPath, g_value_get_string(value), MAX_PATH_LEN);
    } else if (0 == STRCMP(pFieldName, CA_CERT_PATH)) {
        STRNCPY(pIotInfo->caCertPath, g_value_get_string(value), MAX_PATH_LEN);
    } else if (0 == STRCMP(pFieldName, ROLE_ALIASES)) {
        STRNCPY(pIotInfo->roleAlias, g_value_get_string(value), MAX_ROLE_ALIAS_LEN);
    }

CleanUp:

    CHK_LOG_ERR(retStatus);

    return STATUS_SUCCEEDED(retStatus);
}

STATUS gstStructToIotInfo(GstStructure* pGstStruct, PIotInfo pIotInfo)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pGstStruct != NULL && pIotInfo != NULL, STATUS_NULL_ARG);

    MEMSET(pIotInfo, 0x00, SIZEOF(IotInfo));
    CHK(gst_structure_foreach(pGstStruct, setGstIotInfo, pIotInfo), STATUS_INVALID_ARG);

CleanUp:

    return retStatus;
}
