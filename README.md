# openmrs-orthanc-solution

A complete solution for integrating OpenMRS (EHR) and Orthanc (PACS) using Docker Compose to manage and view medical images.

## Table of Contents

- [Introduction](#introduction)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Configuration Details](#configuration-details)
- [Usage](#usage)

## Introduction

This repository provides a robust and reproducible setup for a PACS-EHR integration using Docker Compose. The solution deploys OpenMRS, a leading open-source electronic health record system, alongside Orthanc, a lightweight and powerful DICOM server. The two systems are linked via the OpenMRS Imaging Module, allowing you to manage and view patient-associated DICOM images directly from the OpenMRS patient dashboard.

## Features

- **Docker-based Deployment:** All services run in isolated Docker containers, ensuring consistency and ease of setup on any host with Docker.
- **OpenMRS Reference Application:** A fully functional OpenMRS instance with a MySQL database.
- **Orthanc PACS Server:** An Orthanc instance with a PostgreSQL backend for efficient DICOM storage and indexing.
- **Pre-configured Integration:** The setup includes the necessary modules and configuration to link OpenMRS and Orthanc.
- **ARM64 Architecture Support:** The provided Docker images are compatible with Apple Silicon (M1/M2/M3) and other ARM64 systems.

## Prerequisites

Before you begin, ensure you have the following software installed:

- **Docker Desktop:** The official Docker application for your operating system (Windows, macOS, or Linux).
- **Git:** For cloning this repository.

## Project Structure

```
.
├── openmrs-module-imaging/
├── .gitignore
├── .gitmodules
├── openmrs-docker-compose.yml
├── orthanc-docker-compose.yml
└── README.md
```
- `openmrs-docker-compose.yml`: The file to orchestrate the OpenMRS application and its MySQL database.
- `orthanc-docker-compose.yml`: The file to orchestrate the Orthanc PACS server and its PostgreSQL database.
- `openmrs-module-imaging/`: A directory containing the OpenMRS Imaging Module's files.

## Getting Started

Follow these steps to get the OpenMRS and Orthanc solution up and running.

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/bouzenaali/openmrs-orthanc-integration
    cd openmrs-orthanc-integration
    ```

2.  **Start the Containers:**
    This command will build the images and start all the services in the background.
    ```bash
    docker compose up -d
    ```
    *Wait a few minutes for all services to become healthy before proceeding.*

3.  **Initial OpenMRS Setup:**
    Open your web browser and navigate to the OpenMRS installation wizard.
    ```bash
    http://localhost:8080/openmrs
    ```
    Follow the on-screen instructions to complete the setup. Use the default values, but be sure to connect to the `openmrs-db` container.

4.  **Install Required Modules:**
    - Download the **`webservices.rest-2.48.0.omod`** from [modules.openmrs.org](https://modules.openmrs.org/).
    - Navigate to **Administration > Manage Modules** in OpenMRS.
    - Click "Upload Module," select the downloaded file, and install it.
    - Similarly, download and install the **`openmrs-module-imaging`** module from its source.

5.  **Configure OpenMRS-Orthanc Integration:**
    - After installing the modules, go to the module settings (or global properties) for the Imaging Module.
    - Fill in the configuration details as follows:
        - **ID:** `OpenMRS_Orthanc_PACS`
        - **URL:** `http://orthanc-app:8042`
        - **USERNAME:** `orthanc`
        - **PASSWORD:** `orthanc_admin_password`
        - **PROXY_URL:** (Leave this field blank)

## Configuration Details

This section outlines the key configuration settings in your `docker-compose` and `orthanc.json` files.

### `orthanc-docker-compose.yml` Environment
- **Authentication:** Orthanc is configured with HTTP authentication enabled via environment variables:
  ```yaml
  environment:
    ORTHANC__AUTHENTICATION_ENABLED: "true"
    ORTHANC__REGISTERED_USERS: '{"orthanc": "orthanc_admin_password"}'
orthanc.json

**Plugins**: The Plugins field is set to an empty array to avoid deployment errors:

```JSON
"Plugins": [],
```

PostgreSQL: Orthanc is configured to use the orthanc-db container for its data:

```JSON
"PostgreSQL": {
  "EnableIndex": true,
  "EnableStorage": true,
  "Host": "orthanc-db",
  "Database": "orthanc"
}
```
## Usage

To test the integration, follow these steps:

1.  **Access Dashboards:**

      - OpenMRS: `http://localhost:8080/openmrs`
      - Orthanc Explorer: `http://localhost:8042`

2.  **Send a DICOM Image:**

      - Use a DICOM client or `storescu` to send a test image to Orthanc. The DICOM AE Title is `ORTHANC`, and the address is `localhost:4242`.
      - **Important:** Ensure the DICOM Patient ID `(0010,0020)` in the image matches the OpenMRS ID of a patient you create.

3.  **View Image in OpenMRS:**

      - In OpenMRS, navigate to the patient dashboard for the patient linked to the DICOM image.
      - The Imaging Module should now display the study, allowing you to view the images from Orthanc.



Installing Docker on your Ubuntu server is a straightforward process. Here are the steps to follow using the command line.

-----

### 1\. Update Your System

Open the terminal and run the following commands to ensure all your existing packages are up to date.

```bash
sudo apt update
sudo apt upgrade -y
```

-----

### 2\. Install Dependencies

Install the necessary packages that allow `apt` to use a repository over HTTPS.

```bash
sudo apt install ca-certificates curl gnupg lsb-release -y
```

-----

### 3\. Add Docker's GPG Key

Add Docker's official GPG key to your system to verify the integrity of the downloaded packages.

```bash
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
```

-----

### 4\. Set Up the Docker Repository

Add the Docker repository to your system's `apt` sources.

```bash
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
```

-----

### 5\. Install Docker Engine and Docker Compose

Update your package list again to include the new Docker repository, and then install the Docker Engine and the Docker Compose plugin.

```bash
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-compose-plugin -y
```

-----

### 6\. Verify the Installation

Check that Docker is installed and running correctly.

```bash
sudo docker run hello-world
```

If the installation was successful, you will see a message indicating that your installation appears to be working correctly.

### 7\. Manage Docker Without Sudo (Optional)

To run Docker commands without needing to use `sudo` every time, add your user to the `docker` group.

```bash
sudo usermod -aG docker $USER
```

After running this command, you must **log out and log back in** for the changes to take effect.
