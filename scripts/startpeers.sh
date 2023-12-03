config_file="PeerInfo.cfg"

if [ ! -f "$config_file" ]; then
    echo "Config file not found: $config_file"
    exit 1
fi



while IFS= read -r line || [[ -n "$line" ]]; do
    # skip emty lines
    if [[ -z "$line" ]]; then
        continue
    fi

    read -r id host port flag <<< "$line"

    echo "ID: $id, Host: $host, Port: $port, Flag: $flag"

    ssh $USER@$host <<ENDSSH
        cd project
        java -cp bin PeerProcess $id

ENDSSH
done < "$config_file"
