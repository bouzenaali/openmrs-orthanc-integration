# Require any additional compass plugins here.

# Set this to the root of your project when deployed:
http_path = "/"
css_dir = "../webapp/resources/styles"
sass_dir = "sass"
images_dir = "../webapp/resources/images"
javascripts_dir = "javascripts"
add_import_path = "sass-external/uicommons-scss"

# Important:
# 1) The orthanc.json is for the orthanc server: The orthanc.json file configures the Orthanc server. To enable the worklist element when using the worklist feature
# 2) DefaultConfiguration.json is for Oracle Explorer 2: Disable ability to delete/upload/modify images.
orthanc_config = "../webapp/resources/orthanc_config"