#ifndef __KVS_GST_PLUGIN_UTILS_H__
#define __KVS_GST_PLUGIN_UTILS_H__

#define IOT_GET_CREDENTIAL_ENDPOINT "endpoint"
#define CERTIFICATE_PATH            "cert-path"
#define PRIVATE_KEY_PATH            "key-path"
#define CA_CERT_PATH                "ca-path"
#define ROLE_ALIASES                "role-aliases"

typedef struct __GstTags GstTags;
struct __GstTags {
    UINT32 tagCount;
    Tag tags[MAX_TAG_COUNT];
};
typedef struct __GstTags* PGstTag;

typedef struct __IotInfo IotInfo;
struct __IotInfo {
    CHAR endPoint[MAX_URI_CHAR_LEN + 1];
    CHAR certPath[MAX_PATH_LEN + 1];
    CHAR privateKeyPath[MAX_PATH_LEN + 1];
    CHAR caCertPath[MAX_PATH_LEN + 1];
    CHAR roleAlias[MAX_ROLE_ALIAS_LEN + 1];
};
typedef struct __IotInfo* PIotInfo;

STATUS gstStructToTags(GstStructure*, PGstTag);
gboolean setGstTags(GQuark, const GValue*, gpointer);

STATUS gstStructToIotInfo(GstStructure*, PIotInfo);
gboolean setGstIotInfo(GQuark, const GValue*, gpointer);

#endif //__KVS_GST_PLUGIN_UTILS_H__