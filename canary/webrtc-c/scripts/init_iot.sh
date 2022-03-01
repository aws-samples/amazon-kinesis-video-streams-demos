prefix=$1
export AWS_IOT_CORE_CREDENTIAL_ENDPOINT=$(cat iot-credential-provider.txt)
export AWS_IOT_CORE_CERT=$(pwd)/${prefix}_certificate.pem
export AWS_IOT_CORE_PRIVATE_KEY=$(pwd)/${prefix}_private.key
export AWS_IOT_CORE_ROLE_ALIAS="${prefix}_role_alias"
export AWS_IOT_CORE_THING_NAME="${prefix}_thing"
