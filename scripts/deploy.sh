echo "Generating TAR archive"
git archive --format tar --output ./project.tar demo

echo "Uploading project..."
rsync --progress --stats ./project.tar jacobimmich@storm.cise.ufl.edu:/cise/homes/jacobimmich/project.tar

ssh jacobimmich@storm.cise.ufl.edu << ENDSSH
    mkdir -p project
    rm -rf ./project/*
    tar -xvf ./project.tar -C ./project
ENDSSH
