echo "Generating TAR archive"
git archive --format tar --output ./project.tar demo

echo "Uploading project..."
rsync --progress --stats ./project.tar jacobimmich@storm.cise.ufl.edu:/cise/homes/jacobimmich/project.tar
