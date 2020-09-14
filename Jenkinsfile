pipeline {
    agent {
        docker {
            image 'maven:3-alpine'
            label 'linux && docker'
        }
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -B -U -e clean install'
            }
        }
    }
    post {
        always {
            junit '**/surefire-reports/**/*.xml'
        }
    }
}
