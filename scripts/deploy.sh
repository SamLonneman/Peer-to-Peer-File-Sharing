echo "Generating TAR archive"
git archive --format tar --output ./project.tar main

echo "Uploading project..."
rsync --progress --stats ./project.tar $USER@storm.cise.ufl.edu:/cise/homes/$USER/project.tar

ssh $USER@storm.cise.ufl.edu << ENDSSH
    mkdir -p project
    rm -rf ./project/*
    tar -xvf ./project.tar -C ./project
ENDSSH
