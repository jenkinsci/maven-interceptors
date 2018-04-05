pipeline {
    agent { docker 'maven:3-alpine' } // checking
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
